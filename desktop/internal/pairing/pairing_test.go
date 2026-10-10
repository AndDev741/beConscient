package pairing_test

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/AndDev741/beConscient/desktop/internal/config"
	"github.com/AndDev741/beConscient/desktop/internal/envelope"
	"github.com/AndDev741/beConscient/desktop/internal/pairing"
	"github.com/AndDev741/beConscient/desktop/internal/testserver"
	"github.com/AndDev741/beConscient/desktop/internal/uuidv7"
)

func TestNormalizeCode(t *testing.T) {
	for in, want := range map[string]string{
		"7KQ2-M9XD":     "7KQ2-M9XD",
		"7kq2m9xd\n":    "7KQ2-M9XD",
		" 7KQ2 M9XD ":   "7KQ2-M9XD",
		"OIL0-ABCD":     "0110-ABCD",
		"7kq2-m9xd\r\n": "7KQ2-M9XD",
	} {
		got, err := pairing.NormalizeCode(in)
		if err != nil || got != want {
			t.Fatalf("%q -> %q %v, want %q", in, got, err, want)
		}
	}
	for _, bad := range []string{"", "7KQ2-M9X", "7KQ2-M9XDD", "7KQ2-M9XU"} {
		if _, err := pairing.NormalizeCode(bad); err == nil {
			t.Fatalf("accepted %q", bad)
		}
	}
}

func TestReadCodeFromStdin(t *testing.T) {
	code, err := pairing.ReadCode(strings.NewReader("7kq2-m9xd\n"))
	if err != nil || code != "7KQ2-M9XD" {
		t.Fatalf("%q %v", code, err)
	}
	if _, err := pairing.ReadCode(strings.NewReader("")); err == nil {
		t.Fatal("empty stdin accepted")
	}
}

func TestFingerprintMatchesContractFormula(t *testing.T) {
	secret := make([]byte, 32)
	for i := range secret {
		secret[i] = byte(i)
	}
	id := "0192F3A0-1C2B-7CDE-8F00-112233445566"
	got := pairing.Fingerprint(id, secret)
	if want := testserver.Fingerprint(id, secret); got != want {
		t.Fatalf("fingerprint %s, independent implementation says %s", got, want)
	}
	if len(got) != 9 || got[4] != '-' {
		t.Fatalf("fingerprint %q is not XXXX-XXXX", got)
	}
}

func testKey(srv *testserver.Server) config.KeyFile {
	return config.KeyFile{Kid: srv.Key.Kid, PublicKey: srv.Key.PublicKey, TestOnly: true}
}

func TestPairWithServer(t *testing.T) {
	srv, err := testserver.New()
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()
	srv.PairCodes["7KQ2-M9XD"] = true
	cfg, err := pairing.Pair(context.Background(), pairing.Request{
		Server: srv.URL, Key: testKey(srv), Code: "7kq2 m9xd", Name: "Work laptop", Dev: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if !uuidv7.Valid(cfg.InstallationID) || cfg.InstallationID[14] != '7' {
		t.Fatalf("installation id %q is not a UUIDv7", cfg.InstallationID)
	}
	secret, err := envelope.B64.DecodeString(cfg.Secret)
	if err != nil || len(secret) != 32 {
		t.Fatalf("secret %q", cfg.Secret)
	}
	p := srv.Pairs[0]
	if p["code"] != "7KQ2-M9XD" || p["kind"] != "desktop" || p["name"] != "Work laptop" || p["installation_id"] != cfg.InstallationID || p["secret"] != cfg.Secret {
		t.Fatalf("pair plaintext %v", p)
	}
	if cfg.Fingerprint != testserver.Fingerprint(cfg.InstallationID, secret) || cfg.DeviceName != "Work laptop" {
		t.Fatalf("config %+v", cfg)
	}
	// The code is single use.
	if _, err := pairing.Pair(context.Background(), pairing.Request{Server: srv.URL, Key: testKey(srv), Code: "7KQ2-M9XD", Dev: true}); err == nil ||
		!strings.Contains(err.Error(), "401") {
		t.Fatalf("second use: %v", err)
	}
}

func TestPairRefusesTestKeyAndPlainHTTPByDefault(t *testing.T) {
	srv, err := testserver.New()
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()
	srv.PairCodes["7KQ2-M9XD"] = true
	_, err = pairing.Pair(context.Background(), pairing.Request{Server: srv.URL, Key: testKey(srv), Code: "7KQ2-M9XD"})
	if !errors.Is(err, config.ErrTestKey) {
		t.Fatalf("got %v, want the test key refused", err)
	}
	prod := config.KeyFile{Kid: "prod-1", PublicKey: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"}
	_, err = pairing.Pair(context.Background(), pairing.Request{Server: srv.URL, Key: prod, Code: "7KQ2-M9XD"})
	if err == nil || !strings.Contains(err.Error(), "https") {
		t.Fatalf("got %v, want http refused", err)
	}
	if len(srv.Pairs) != 0 {
		t.Fatal("something reached the server")
	}
}
