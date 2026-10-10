package config_test

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/AndDev741/beConscient/desktop/internal/config"
	"github.com/AndDev741/beConscient/desktop/internal/testserver"
)

func TestTestKeyRefusedUnlessDev(t *testing.T) {
	dir, err := testserver.ContractDir()
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(dir, "keys", "test-public.json"))
	if err != nil {
		t.Fatal(err)
	}
	k, err := config.ParseKey(data)
	if err != nil {
		t.Fatal(err)
	}
	if !k.IsTest() {
		t.Fatal("contract test key not recognised")
	}
	if err := config.CheckKey(k, false); !errors.Is(err, config.ErrTestKey) {
		t.Fatalf("default accepted the test key: %v", err)
	}
	if err := config.CheckKey(k, true); err != nil {
		t.Fatalf("--dev refused the test key: %v", err)
	}
	// Renaming the kid or dropping the flag does not hide the test key.
	k.TestOnly, k.Kid = false, "prod-2026-10"
	if err := config.CheckKey(k, false); !errors.Is(err, config.ErrTestKey) {
		t.Fatal("test key bytes accepted under another kid")
	}
	prod := config.KeyFile{Kid: "prod-2026-10", PublicKey: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"}
	if err := config.CheckKey(prod, false); err != nil {
		t.Fatalf("a production key was refused: %v", err)
	}
}

func TestParseKeyRejectsBadFiles(t *testing.T) {
	for _, bad := range []string{
		`{}`,
		`{"kid":"x","public_key":"not-base64!"}`,
		`{"kid":"x","public_key":"AAAA"}`,
		`{"kid":"x","public_key":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","suite":"P-256"}`,
	} {
		if _, err := config.ParseKey([]byte(bad)); err == nil {
			t.Fatalf("accepted %s", bad)
		}
	}
}

func TestServerURL(t *testing.T) {
	if u, err := config.CheckServerURL("https://screen.example.org/", false); err != nil || u != "https://screen.example.org" {
		t.Fatalf("%q %v", u, err)
	}
	for _, bad := range []string{"http://screen.example.org", "ftp://x", "https://user:pw@x", "https://x/?a=1", "x"} {
		if _, err := config.CheckServerURL(bad, false); err == nil {
			t.Fatalf("accepted %q", bad)
		}
	}
	if _, err := config.CheckServerURL("http://127.0.0.1:8080", true); err != nil {
		t.Fatal("--dev must allow http")
	}
}

func TestSaveIsPrivate(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "config", "beconscient")
	p := filepath.Join(dir, "agent.json")
	c := &config.Config{Server: "https://screen.example.org", Kid: "prod-1", PublicKey: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
		InstallationID: "0192f3a0-1c2b-7cde-8f00-112233445566", Secret: "c2VjcmV0"}
	if err := config.Save(p, c); err != nil {
		t.Fatal(err)
	}
	fi, _ := os.Stat(p)
	di, _ := os.Stat(dir)
	if fi.Mode().Perm() != 0o600 || di.Mode().Perm() != 0o700 {
		t.Fatalf("modes file %o dir %o", fi.Mode().Perm(), di.Mode().Perm())
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 1 {
		t.Fatalf("leftover temp files: %v", entries)
	}
	got, err := config.Load(p)
	if err != nil || got.Secret != c.Secret || got.InstallationID != c.InstallationID {
		t.Fatalf("load %+v %v", got, err)
	}
	if _, err := config.Load(filepath.Join(dir, "missing.json")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("missing config: %v", err)
	}
}
