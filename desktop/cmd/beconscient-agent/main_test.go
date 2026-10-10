package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/AndDev741/beConscient/desktop/internal/config"
	"github.com/AndDev741/beConscient/desktop/internal/testserver"
)

func isolate(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(dir, "config"))
	t.Setenv("XDG_STATE_HOME", filepath.Join(dir, "state"))
	t.Setenv("XDG_RUNTIME_DIR", filepath.Join(dir, "run"))
	return dir
}

func TestHostModeRefusesWrongOrigin(t *testing.T) {
	isolate(t)
	if code := runHost([]string{"chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/"}); code != 1 {
		t.Fatalf("exit %d, want 1", code)
	}
}

func TestPairNeverTakesTheCodeFromArgv(t *testing.T) {
	isolate(t)
	code := runPair([]string{"--server", "https://screen.example.org", "--key", "k.json", "7KQ2-M9XD"}, strings.NewReader(""))
	if code != 1 {
		t.Fatalf("exit %d, want 1", code)
	}
}

func TestPairRefusesTestKeyWithoutDev(t *testing.T) {
	isolate(t)
	dir, err := testserver.ContractDir()
	if err != nil {
		t.Fatal(err)
	}
	key := filepath.Join(dir, "keys", "test-public.json")
	if code := runPair([]string{"--server", "https://screen.example.org", "--key", key}, strings.NewReader("7KQ2-M9XD\n")); code != 1 {
		t.Fatalf("exit %d, want 1", code)
	}
}

func TestRunRefusesTestKeyWithoutDev(t *testing.T) {
	base := isolate(t)
	tk, _, err := testserver.LoadTestKey()
	if err != nil {
		t.Fatal(err)
	}
	cfg := &config.Config{Server: "https://screen.example.org", Kid: tk.Kid, PublicKey: tk.PublicKey, TestOnly: true,
		InstallationID: "0192f3a0-1c2b-7cde-8f00-112233445566", Secret: "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"}
	if err := config.Save(filepath.Join(base, "config", "beconscient", "agent.json"), cfg); err != nil {
		t.Fatal(err)
	}
	if code := runAgent(nil); code != 1 {
		t.Fatalf("exit %d, want the test key refused", code)
	}
	// Nothing was started: no queue was created.
	if _, err := os.Stat(filepath.Join(base, "state", "beconscient", "queue.db")); !os.IsNotExist(err) {
		t.Fatal("the agent started with the test key")
	}
}

func TestStatusAndReplayWorkWithoutServer(t *testing.T) {
	isolate(t)
	if code := runStatus(); code != 0 {
		t.Fatalf("status exit %d", code)
	}
	if code := runReplay([]string{"--since", "2026-10-01"}); code != 0 {
		t.Fatalf("replay exit %d", code)
	}
	if code := runReplay([]string{"--since", "yesterday"}); code != 1 {
		t.Fatalf("bad date exit %d", code)
	}
}

func TestParseSince(t *testing.T) {
	if _, err := parseSince("2026-10-10"); err != nil {
		t.Fatal(err)
	}
	if ts, err := parseSince("2026-10-10T08:00:00Z"); err != nil || ts.UTC().Hour() != 8 {
		t.Fatalf("%v %v", ts, err)
	}
}
