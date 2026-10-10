// Package envelope seals requests to the server and opens its encrypted
// responses, as described in contract/envelope.md.
//
// Suite: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305, base mode.
// The AEAD aad of the request is the UTF-8 kid; the HPKE info names the
// purpose. Responses are sealed with Export("beconscient/v1/response", 32).
package envelope

import (
	"crypto/ecdh"
	"crypto/hpke"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"

	"golang.org/x/crypto/chacha20poly1305"
)

// HPKE info strings, one per purpose.
const (
	InfoPair  = "beconscient/v1/pair"
	InfoBatch = "beconscient/v1/batch"
)

// ResponseContext is both the exporter context of the response key and the
// aad of the response AEAD.
const ResponseContext = "beconscient/v1/response"

// MaxBody is the largest request body the proxy and server accept.
const MaxBody = 1 << 20

// B64 is unpadded base64url (RFC 4648 section 5), used for every binary field.
var B64 = base64.RawURLEncoding

// Request is the JSON body of every POST to /ingest/*.
type Request struct {
	V   int    `json:"v"`
	Kid string `json:"kid"`
	Enc string `json:"enc"`
	Ct  string `json:"ct"`
}

// Response is the JSON body of every 200 from /ingest/*.
type Response struct {
	V     int    `json:"v"`
	Nonce string `json:"nonce"`
	Ct    string `json:"ct"`
}

// Key is a server public key with its kid.
type Key struct {
	Kid string
	pub hpke.PublicKey
	raw []byte
}

// NewKey parses a raw 32-byte X25519 public key.
func NewKey(kid string, raw []byte) (*Key, error) {
	if kid == "" {
		return nil, errors.New("empty kid")
	}
	ec, err := ecdh.X25519().NewPublicKey(raw)
	if err != nil {
		return nil, fmt.Errorf("public key: %w", err)
	}
	pk, err := hpke.NewDHKEMPublicKey(ec)
	if err != nil {
		return nil, err
	}
	return &Key{Kid: kid, pub: pk, raw: append([]byte(nil), raw...)}, nil
}

// NewKeyB64 parses a base64url public key.
func NewKeyB64(kid, b64 string) (*Key, error) {
	raw, err := B64.DecodeString(b64)
	if err != nil {
		return nil, fmt.Errorf("public key encoding: %w", err)
	}
	return NewKey(kid, raw)
}

// Sealed is one sealed request together with the key that opens its response.
type Sealed struct {
	Request     Request
	responseKey []byte
}

// Seal encrypts plaintext for the given purpose (InfoPair or InfoBatch).
func Seal(k *Key, info string, plaintext []byte) (*Sealed, error) {
	enc, s, err := hpke.NewSender(k.pub, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), []byte(info))
	if err != nil {
		return nil, fmt.Errorf("hpke sender: %w", err)
	}
	ct, err := s.Seal([]byte(k.Kid), plaintext)
	if err != nil {
		return nil, fmt.Errorf("hpke seal: %w", err)
	}
	key, err := s.Export(ResponseContext, 32)
	if err != nil {
		return nil, fmt.Errorf("hpke export: %w", err)
	}
	return &Sealed{
		Request:     Request{V: 1, Kid: k.Kid, Enc: B64.EncodeToString(enc), Ct: B64.EncodeToString(ct)},
		responseKey: key,
	}, nil
}

// Body returns the JSON request body.
func (s *Sealed) Body() ([]byte, error) { return json.Marshal(s.Request) }

// ResponseKey returns a copy of the exported response key.
func (s *Sealed) ResponseKey() []byte { return append([]byte(nil), s.responseKey...) }

// Open decrypts a 200 response body that answers this request. A response
// that was not made from this request's HPKE context fails to open.
func (s *Sealed) Open(body []byte) ([]byte, error) { return OpenResponse(s.responseKey, body) }

// OpenResponse decrypts a response body with an exported response key.
func OpenResponse(key, body []byte) ([]byte, error) {
	var r Response
	if err := json.Unmarshal(body, &r); err != nil {
		return nil, fmt.Errorf("response is not an envelope: %w", err)
	}
	if r.V != 1 {
		return nil, fmt.Errorf("response version %d", r.V)
	}
	nonce, err := B64.DecodeString(r.Nonce)
	if err != nil || len(nonce) != chacha20poly1305.NonceSize {
		return nil, errors.New("response nonce is invalid")
	}
	ct, err := B64.DecodeString(r.Ct)
	if err != nil {
		return nil, errors.New("response ciphertext encoding is invalid")
	}
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	pt, err := aead.Open(nil, nonce, ct, []byte(ResponseContext))
	if err != nil {
		return nil, errors.New("response does not open with this request's key")
	}
	return pt, nil
}
