// Command vectorcheck opens every envelope test vector in contract/vectors with
// the test-only private key and checks the plaintext, the exported response key
// and the encrypted response. Each implementation writes its own vector file, so
// this proves that all of them agree on the envelope.
package main

import (
	"crypto/ecdh"
	"crypto/hpke"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"

	"golang.org/x/crypto/chacha20poly1305"
)

type envelope struct {
	Kid   string `json:"kid"`
	Enc   string `json:"enc"`
	Ct    string `json:"ct"`
	Nonce string `json:"nonce"`
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
	keyPath := flag.String("key", "../contract/keys/test-keypair.json", "test key pair file")
	dir := flag.String("dir", "../contract/vectors", "vector directory")
	flag.Parse()

	raw, err := os.ReadFile(*keyPath)
	must(err)
	var kf struct {
		Kid        string `json:"kid"`
		PrivateKey string `json:"private_key"`
	}
	must(json.Unmarshal(raw, &kf))
	privBytes, err := b64.DecodeString(kf.PrivateKey)
	must(err)
	ecPriv, err := ecdh.X25519().NewPrivateKey(privBytes)
	must(err)
	sk, err := hpke.NewDHKEMPrivateKey(ecPriv)
	must(err)

	files, err := filepath.Glob(filepath.Join(*dir, "*.json"))
	must(err)
	failures := 0
	for _, f := range files {
		data, err := os.ReadFile(f)
		must(err)
		var vs []vector
		must(json.Unmarshal(data, &vs))
		for _, v := range vs {
			if err := verify(sk, kf.Kid, v); err != nil {
				failures++
				fmt.Printf("FAIL %s %s/%s: %v\n", filepath.Base(f), v.Producer, v.Purpose, err)
			} else {
				fmt.Printf("ok   %s %s/%s\n", filepath.Base(f), v.Producer, v.Purpose)
			}
		}
	}
	if failures > 0 {
		os.Exit(1)
	}
}

func verify(sk hpke.PrivateKey, kid string, v vector) error {
	if v.Request.Kid != kid {
		return fmt.Errorf("kid %q is not the test kid", v.Request.Kid)
	}
	if v.Info != "beconscient/v1/"+v.Purpose {
		return fmt.Errorf("info %q does not match purpose", v.Info)
	}
	enc, err := b64.DecodeString(v.Request.Enc)
	if err != nil {
		return err
	}
	ct, err := b64.DecodeString(v.Request.Ct)
	if err != nil {
		return err
	}
	r, err := hpke.NewRecipient(enc, sk, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), []byte(v.Info))
	if err != nil {
		return err
	}
	pt, err := r.Open([]byte(kid), ct)
	if err != nil {
		return fmt.Errorf("open request: %w", err)
	}
	if string(pt) != v.Plaintext {
		return fmt.Errorf("plaintext mismatch")
	}
	key, err := r.Export("beconscient/v1/response", 32)
	if err != nil {
		return err
	}
	if b64.EncodeToString(key) != v.ResponseKey {
		return fmt.Errorf("response key mismatch")
	}
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return err
	}
	nonce, err := b64.DecodeString(v.Response.Nonce)
	if err != nil {
		return err
	}
	rct, err := b64.DecodeString(v.Response.Ct)
	if err != nil {
		return err
	}
	rpt, err := aead.Open(nil, nonce, rct, []byte("beconscient/v1/response"))
	if err != nil {
		return fmt.Errorf("open response: %w", err)
	}
	if string(rpt) != v.ResponsePlaintext {
		return fmt.Errorf("response plaintext mismatch")
	}
	return nil
}

func must(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
