// Command keygen creates an X25519 HPKE key pair for the beConscient envelope.
//
// It prints a public key file (safe to commit) and writes the private key file
// with 0600 permissions to the path given in -private. Never commit the private
// key of a non-test key pair.
package main

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"os"
)

type keyFile struct {
	Kid        string `json:"kid"`
	Suite      string `json:"suite"`
	PublicKey  string `json:"public_key"`
	PrivateKey string `json:"private_key,omitempty"`
	TestOnly   bool   `json:"test_only,omitempty"`
}

const suite = "DHKEM(X25519,HKDF-SHA256)/HKDF-SHA256/ChaCha20Poly1305"

func main() {
	kid := flag.String("kid", "", "key id, for example prod-2026-10")
	publicPath := flag.String("public", "", "where to write the public key file")
	privatePath := flag.String("private", "", "where to write the private key file (0600)")
	testOnly := flag.Bool("test-only", false, "mark the key pair as test-only")
	flag.Parse()
	if *kid == "" || *publicPath == "" || *privatePath == "" {
		fmt.Fprintln(os.Stderr, "usage: keygen -kid <id> -public <file> -private <file> [-test-only]")
		os.Exit(2)
	}

	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		fail(err)
	}
	enc := base64.RawURLEncoding
	pub := keyFile{Kid: *kid, Suite: suite, PublicKey: enc.EncodeToString(priv.PublicKey().Bytes()), TestOnly: *testOnly}
	full := pub
	full.PrivateKey = enc.EncodeToString(priv.Bytes())

	write(*publicPath, pub, 0o644)
	write(*privatePath, full, 0o600)
}

func write(path string, v keyFile, mode os.FileMode) {
	data, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		fail(err)
	}
	if err := os.WriteFile(path, append(data, '\n'), mode); err != nil {
		fail(err)
	}
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, err)
	os.Exit(1)
}
