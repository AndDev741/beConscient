// Package config stores the agent's pairing (server URL, server key,
// installation id and secret) in $XDG_CONFIG_HOME/beconscient/agent.json with
// mode 0600, and parses server public key files.
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"os"
	"strings"

	"github.com/AndDev741/beConscient/desktop/internal/envelope"
	"github.com/AndDev741/beConscient/desktop/internal/paths"
)

// TestKid and TestPublicKey identify the public, test-only key pair in
// contract/keys. Production refuses it and so does this agent unless --dev.
const (
	TestKid       = "test-0001"
	TestPublicKey = "y8OzVaM2hnilfWsxNBWGHCufC6d3x4qoX1CXPhGhy3A"
)

// ErrTestKey is returned when a test-only key is used without --dev.
var ErrTestKey = errors.New("refusing the test-only server key: it is public, so anyone could read these batches (use --dev only for development)")

// KeyFile is the public key file written by tools/cmd/keygen.
type KeyFile struct {
	Kid       string `json:"kid"`
	Suite     string `json:"suite,omitempty"`
	PublicKey string `json:"public_key"`
	TestOnly  bool   `json:"test_only,omitempty"`
}

// Suite is the only supported HPKE suite.
const Suite = "DHKEM(X25519,HKDF-SHA256)/HKDF-SHA256/ChaCha20Poly1305"

// ParseKey parses and validates a public key file.
func ParseKey(data []byte) (KeyFile, error) {
	var k KeyFile
	if err := json.Unmarshal(data, &k); err != nil {
		return k, fmt.Errorf("key file: %w", err)
	}
	if k.Kid == "" || k.PublicKey == "" {
		return k, errors.New("key file needs kid and public_key")
	}
	if k.Suite != "" && k.Suite != Suite {
		return k, fmt.Errorf("unsupported suite %q", k.Suite)
	}
	if _, err := envelope.NewKeyB64(k.Kid, k.PublicKey); err != nil {
		return k, err
	}
	return k, nil
}

// IsTest reports whether the key is the test-only key, by flag, kid or bytes.
func (k KeyFile) IsTest() bool {
	return k.TestOnly || k.Kid == TestKid || strings.HasPrefix(k.Kid, "test-") || k.PublicKey == TestPublicKey
}

// CheckKey refuses a test-only key unless dev is set.
func CheckKey(k KeyFile, dev bool) error {
	if k.IsTest() && !dev {
		return ErrTestKey
	}
	return nil
}

// CheckServerURL accepts https URLs, and http only in dev mode.
func CheckServerURL(raw string, dev bool) (string, error) {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return "", fmt.Errorf("invalid server url %q", raw)
	}
	if u.Scheme != "https" && !(dev && u.Scheme == "http") {
		return "", fmt.Errorf("server url must use https (got %q)", u.Scheme)
	}
	if u.RawQuery != "" || u.Fragment != "" || u.User != nil {
		return "", errors.New("server url must not carry credentials, a query or a fragment")
	}
	return strings.TrimRight(u.String(), "/"), nil
}

// Config is agent.json.
type Config struct {
	Server         string `json:"server"`
	Kid            string `json:"kid"`
	PublicKey      string `json:"public_key"`
	TestOnly       bool   `json:"test_only,omitempty"`
	InstallationID string `json:"installation_id"`
	Secret         string `json:"secret"`
	DeviceID       string `json:"device_id,omitempty"`
	DeviceName     string `json:"device_name,omitempty"`
	Fingerprint    string `json:"fingerprint,omitempty"`
	PairedAt       int64  `json:"paired_at,omitempty"`
}

// Key returns the server key as a KeyFile.
func (c *Config) Key() KeyFile {
	return KeyFile{Kid: c.Kid, PublicKey: c.PublicKey, TestOnly: c.TestOnly, Suite: Suite}
}

// EnvelopeKey returns the parsed server key.
func (c *Config) EnvelopeKey() (*envelope.Key, error) { return envelope.NewKeyB64(c.Kid, c.PublicKey) }

// Validate checks that the config is complete.
func (c *Config) Validate() error {
	switch {
	case c.Server == "":
		return errors.New("config has no server")
	case c.InstallationID == "":
		return errors.New("config has no installation id")
	case c.Secret == "":
		return errors.New("config has no secret")
	}
	if _, err := c.EnvelopeKey(); err != nil {
		return err
	}
	return nil
}

// Load reads agent.json. It returns os.ErrNotExist (wrapped) when the agent
// is not paired yet.
func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var c Config
	if err := json.Unmarshal(data, &c); err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	if err := c.Validate(); err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	return &c, nil
}

// Save writes agent.json with mode 0600 in a 0700 directory.
func Save(path string, c *Config) error {
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return paths.WritePrivateFile(path, append(data, '\n'))
}
