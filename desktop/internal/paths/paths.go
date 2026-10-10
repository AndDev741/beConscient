// Package paths resolves the agent's XDG directories and creates private
// directories (0700) and files (0600) for its state, configuration and socket.
package paths

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"syscall"
)

// AppDir is the directory name used under every XDG base directory.
const AppDir = "beconscient"

// Dir and file modes for everything the agent creates.
const (
	DirMode  os.FileMode = 0o700
	FileMode os.FileMode = 0o600
)

func xdg(env, fallback string) (string, error) {
	if v := os.Getenv(env); v != "" && filepath.IsAbs(v) {
		return filepath.Join(v, AppDir), nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("resolve home directory: %w", err)
	}
	return filepath.Join(home, fallback, AppDir), nil
}

// StateDir returns $XDG_STATE_HOME/beconscient (default ~/.local/state/beconscient).
func StateDir() (string, error) { return xdg("XDG_STATE_HOME", ".local/state") }

// ConfigDir returns $XDG_CONFIG_HOME/beconscient (default ~/.config/beconscient).
func ConfigDir() (string, error) { return xdg("XDG_CONFIG_HOME", ".config") }

// RuntimeDir returns $XDG_RUNTIME_DIR/beconscient, falling back to
// /run/user/<uid>/beconscient when the variable is unset.
func RuntimeDir() string {
	if v := os.Getenv("XDG_RUNTIME_DIR"); v != "" && filepath.IsAbs(v) {
		return filepath.Join(v, AppDir)
	}
	return filepath.Join("/run/user", strconv.Itoa(os.Getuid()), AppDir)
}

// QueuePath is the SQLite queue inside the state directory.
func QueuePath() (string, error) {
	d, err := StateDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(d, "queue.db"), nil
}

// ConfigPath is agent.json inside the config directory.
func ConfigPath() (string, error) {
	d, err := ConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(d, "agent.json"), nil
}

// SocketPath is the agent's Unix socket inside the runtime directory.
func SocketPath() string { return filepath.Join(RuntimeDir(), "agent.sock") }

// EnsurePrivateDir creates dir (and missing parents) and makes sure the leaf
// is a real directory owned by the current user with mode 0700.
func EnsurePrivateDir(dir string) error {
	if err := os.MkdirAll(dir, DirMode); err != nil {
		return fmt.Errorf("create %s: %w", dir, err)
	}
	fi, err := os.Lstat(dir)
	if err != nil {
		return err
	}
	if !fi.IsDir() {
		return fmt.Errorf("%s is not a directory", dir)
	}
	if st, ok := fi.Sys().(*syscall.Stat_t); ok && int(st.Uid) != os.Getuid() {
		return fmt.Errorf("%s is owned by uid %d, not by the current user", dir, st.Uid)
	}
	if fi.Mode().Perm() != DirMode {
		if err := os.Chmod(dir, DirMode); err != nil {
			return fmt.Errorf("chmod %s: %w", dir, err)
		}
	}
	return nil
}

// EnsurePrivateFile creates path with mode 0600 when it does not exist and
// tightens its mode to 0600 when it does. The parent must already exist.
func EnsurePrivateFile(path string) error {
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE, FileMode)
	if err != nil {
		return fmt.Errorf("create %s: %w", path, err)
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return err
	}
	if !fi.Mode().IsRegular() {
		return fmt.Errorf("%s is not a regular file", path)
	}
	if fi.Mode().Perm() != FileMode {
		return f.Chmod(FileMode)
	}
	return nil
}

// WritePrivateFile atomically replaces path with data, mode 0600, creating the
// parent directory with mode 0700.
func WritePrivateFile(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := EnsurePrivateDir(dir); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".tmp-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	ok := false
	defer func() {
		if !ok {
			tmp.Close()
			os.Remove(name)
		}
	}()
	if err := tmp.Chmod(FileMode); err != nil {
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		return err
	}
	if err := tmp.Sync(); err != nil {
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(name, path); err != nil {
		return err
	}
	ok = true
	return nil
}
