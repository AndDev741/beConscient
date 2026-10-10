package paths_test

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/AndDev741/beConscient/desktop/internal/paths"
)

func TestXDGDirectories(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", "/x/state")
	t.Setenv("XDG_CONFIG_HOME", "/x/config")
	t.Setenv("XDG_RUNTIME_DIR", "/x/run")
	if p, _ := paths.QueuePath(); p != "/x/state/beconscient/queue.db" {
		t.Fatal(p)
	}
	if p, _ := paths.ConfigPath(); p != "/x/config/beconscient/agent.json" {
		t.Fatal(p)
	}
	if p := paths.SocketPath(); p != "/x/run/beconscient/agent.sock" {
		t.Fatal(p)
	}
	// Relative values are ignored, as the XDG spec says.
	t.Setenv("XDG_STATE_HOME", "relative")
	t.Setenv("HOME", "/home/u")
	if p, _ := paths.StateDir(); p != "/home/u/.local/state/beconscient" {
		t.Fatal(p)
	}
}

func TestPrivateFilesAndDirs(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "a", "b")
	if err := paths.EnsurePrivateDir(dir); err != nil {
		t.Fatal(err)
	}
	f := filepath.Join(dir, "secret.json")
	if err := paths.WritePrivateFile(f, []byte("{}")); err != nil {
		t.Fatal(err)
	}
	os.Chmod(f, 0o644)
	if err := paths.EnsurePrivateFile(f); err != nil {
		t.Fatal(err)
	}
	fi, _ := os.Stat(f)
	di, _ := os.Stat(dir)
	if fi.Mode().Perm() != 0o600 || di.Mode().Perm() != 0o700 {
		t.Fatalf("modes %o %o", fi.Mode().Perm(), di.Mode().Perm())
	}
	// A file where a directory should be is refused.
	if err := paths.EnsurePrivateDir(f); err == nil {
		t.Fatal("a file accepted as a directory")
	}
}
