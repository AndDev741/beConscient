// Command vectorgen writes a reference test vector for the beConscient envelope.
//
// It seals a sample batch and a sample pairing request to the test-only public
// key, exports the response key from the sender context and encrypts a sample
// receipt with it. Every implementation verifies these vectors in its tests.
package main

import (
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hpke"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"os"

	"golang.org/x/crypto/chacha20poly1305"
)

type keyFile struct {
	Kid       string `json:"kid"`
	PublicKey string `json:"public_key"`
}

type envelope struct {
	V     int    `json:"v"`
	Kid   string `json:"kid,omitempty"`
	Enc   string `json:"enc,omitempty"`
	Ct    string `json:"ct"`
	Nonce string `json:"nonce,omitempty"`
}

type vector struct {
	Producer          string   `json:"producer"`
	Kid               string   `json:"kid"`
	Purpose           string   `json:"purpose"`
	Info              string   `json:"info"`
	Plaintext         string   `json:"plaintext"`
	Request           envelope `json:"request"`
	ResponseKey       string   `json:"response_key"`
	ResponsePlaintext string   `json:"response_plaintext"`
	Response          envelope `json:"response"`
}

var b64 = base64.RawURLEncoding

func main() {
	keyPath := flag.String("key", "../contract/keys/test-public.json", "test public key file")
	out := flag.String("out", "../contract/vectors/go-reference.json", "output file")
	flag.Parse()

	raw, err := os.ReadFile(*keyPath)
	check(err)
	var kf keyFile
	check(json.Unmarshal(raw, &kf))
	pubBytes, err := b64.DecodeString(kf.PublicKey)
	check(err)
	ecPub, err := ecdh.X25519().NewPublicKey(pubBytes)
	check(err)
	pk, err := hpke.NewDHKEMPublicKey(ecPub)
	check(err)

	batch := `{"header":{"installation_id":"0192f3a0-1c2b-7cde-8f00-112233445566","credential":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","seq":1,"sent_at":1760090000123,"tz":"Europe/Lisbon","tz_offset_min":60,"contract_version":1,"client_version":"0.0.0-test"},"events":[{"id":"0192f3a1-0000-7000-8000-000000000001","type":"android.usage","at":1760089000000,"kind":"ACTIVITY_RESUMED","package":"com.example.app","class":"com.example.app.MainActivity"}]}`
	receipt := `{"installation_id":"0192f3a0-1c2b-7cde-8f00-112233445566","seq":1,"results":[{"id":"0192f3a1-0000-7000-8000-000000000001","status":"accepted"}]}`
	pair := `{"code":"7KQ2-M9XD","installation_id":"0192f3a0-1c2b-7cde-8f00-112233445566","secret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","kind":"desktop","name":"Test desktop","client_version":"0.0.0-test"}`
	pairResponse := `{"device_id":"0192f3a0-0000-7000-8000-0000000000aa","device_name":"Test desktop","fingerprint":"ABCD-EFGH"}`

	vectors := []vector{
		build(pk, kf.Kid, "batch", batch, receipt),
		build(pk, kf.Kid, "pair", pair, pairResponse),
	}
	data, err := json.MarshalIndent(vectors, "", "  ")
	check(err)
	check(os.WriteFile(*out, append(data, '\n'), 0o644))
	fmt.Println("wrote", *out)
}

func build(pk hpke.PublicKey, kid, purpose, plaintext, response string) vector {
	info := "beconscient/v1/" + purpose
	enc, sender, err := hpke.NewSender(pk, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), []byte(info))
	check(err)
	ct, err := sender.Seal([]byte(kid), []byte(plaintext))
	check(err)
	key, err := sender.Export("beconscient/v1/response", 32)
	check(err)
	aead, err := chacha20poly1305.New(key)
	check(err)
	nonce := make([]byte, aead.NonceSize())
	_, err = rand.Read(nonce)
	check(err)
	var a cipher.AEAD = aead
	rct := a.Seal(nil, nonce, []byte(response), []byte("beconscient/v1/response"))
	return vector{
		Producer: "go-reference", Kid: kid, Purpose: purpose, Info: info, Plaintext: plaintext,
		Request:           envelope{V: 1, Kid: kid, Enc: b64.EncodeToString(enc), Ct: b64.EncodeToString(ct)},
		ResponseKey:       b64.EncodeToString(key),
		ResponsePlaintext: response,
		Response:          envelope{V: 1, Nonce: b64.EncodeToString(nonce), Ct: b64.EncodeToString(rct)},
	}
}

func check(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
