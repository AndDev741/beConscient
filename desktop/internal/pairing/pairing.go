// Package pairing pairs this installation with the server using a one-time
// code (contract/envelope.md, "Pairing").
package pairing

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
	"unicode"

	"github.com/AndDev741/beConscient/desktop/internal/config"
	"github.com/AndDev741/beConscient/desktop/internal/envelope"
	"github.com/AndDev741/beConscient/desktop/internal/uuidv7"
	"github.com/AndDev741/beConscient/desktop/internal/version"
)

// PairPath is the pairing endpoint below the server URL.
const PairPath = "/ingest/v1/pair"

// crockford is the Crockford base32 alphabet.
const crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// NormalizeCode turns user input into the canonical XXXX-XXXX form. It
// accepts lowercase, spaces and hyphens, and maps the Crockford look-alikes
// O to 0 and I, L to 1.
func NormalizeCode(in string) (string, error) {
	var b strings.Builder
	for _, r := range strings.ToUpper(in) {
		switch {
		case r == '-' || unicode.IsSpace(r):
			continue
		case r == 'O':
			r = '0'
		case r == 'I' || r == 'L':
			r = '1'
		}
		if !strings.ContainsRune(crockford, r) {
			return "", fmt.Errorf("pairing code contains %q, which is not Crockford base32", r)
		}
		b.WriteRune(r)
	}
	s := b.String()
	if len(s) != 8 {
		return "", fmt.Errorf("pairing code must have 8 characters, got %d", len(s))
	}
	return s[:4] + "-" + s[4:], nil
}

// ReadCode reads the pairing code from the first line of r (stdin). The code
// is never taken from the command line, where other processes could read it.
func ReadCode(r io.Reader) (string, error) {
	line, err := bufio.NewReader(io.LimitReader(r, 256)).ReadString('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return "", err
	}
	if strings.TrimSpace(line) == "" {
		return "", errors.New("no pairing code on standard input")
	}
	return NormalizeCode(line)
}

// Fingerprint is the first 8 Crockford base32 characters of
// SHA-256("beconscient-fp" || installation_id || SHA-256(secret)), shown as
// XXXX-XXXX.
func Fingerprint(installationID string, secret []byte) string {
	sh := sha256.Sum256(secret)
	h := sha256.New()
	h.Write([]byte("beconscient-fp"))
	h.Write([]byte(strings.ToLower(installationID)))
	h.Write(sh[:])
	s := base32Crockford(h.Sum(nil))[:8]
	return s[:4] + "-" + s[4:]
}

func base32Crockford(data []byte) string {
	var out strings.Builder
	var buf uint64
	bits := 0
	for _, c := range data {
		buf = buf<<8 | uint64(c)
		bits += 8
		for bits >= 5 {
			out.WriteByte(crockford[(buf>>(bits-5))&31])
			bits -= 5
		}
	}
	if bits > 0 {
		out.WriteByte(crockford[(buf<<(5-bits))&31])
	}
	return out.String()
}

func sameFingerprint(a, b string) bool {
	clean := func(s string) string { return strings.ReplaceAll(strings.ToUpper(strings.TrimSpace(s)), "-", "") }
	return clean(a) == clean(b)
}

// Request holds everything a pairing needs.
type Request struct {
	Server string
	Key    config.KeyFile
	Code   string
	Name   string
	Dev    bool
	HTTP   *http.Client
	Now    func() time.Time
}

type pairPlaintext struct {
	Code           string `json:"code"`
	InstallationID string `json:"installation_id"`
	Secret         string `json:"secret"`
	Kind           string `json:"kind"`
	Name           string `json:"name"`
	ClientVersion  string `json:"client_version"`
}

type pairResponse struct {
	DeviceID    string `json:"device_id"`
	DeviceName  string `json:"device_name"`
	Fingerprint string `json:"fingerprint"`
}

// BuildPlaintext returns the pairing plaintext.
func BuildPlaintext(code, installationID string, secret []byte, name string) ([]byte, error) {
	return json.Marshal(pairPlaintext{
		Code: code, InstallationID: installationID, Secret: envelope.B64.EncodeToString(secret),
		Kind: "desktop", Name: name, ClientVersion: version.Version,
	})
}

// Pair runs the pairing exchange and returns the new configuration. The
// caller saves it.
func Pair(ctx context.Context, req Request) (*config.Config, error) {
	if req.HTTP == nil {
		req.HTTP = &http.Client{Timeout: 30 * time.Second}
	}
	if req.Now == nil {
		req.Now = time.Now
	}
	if err := config.CheckKey(req.Key, req.Dev); err != nil {
		return nil, err
	}
	server, err := config.CheckServerURL(req.Server, req.Dev)
	if err != nil {
		return nil, err
	}
	code, err := NormalizeCode(req.Code)
	if err != nil {
		return nil, err
	}
	key, err := envelope.NewKeyB64(req.Key.Kid, req.Key.PublicKey)
	if err != nil {
		return nil, err
	}
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		return nil, err
	}
	installationID := uuidv7.New(req.Now())
	name := strings.TrimSpace(req.Name)
	if name == "" {
		name = "Linux desktop"
	}
	pt, err := BuildPlaintext(code, installationID, secret, name)
	if err != nil {
		return nil, err
	}
	sealed, err := envelope.Seal(key, envelope.InfoPair, pt)
	if err != nil {
		return nil, err
	}
	body, err := sealed.Body()
	if err != nil {
		return nil, err
	}
	hreq, err := http.NewRequestWithContext(ctx, http.MethodPost, server+PairPath, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	hreq.Header.Set("Content-Type", "application/json")
	hreq.Header.Set("User-Agent", "beconscient-agent/"+version.Version)
	resp, err := req.HTTP.Do(hreq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		var e struct {
			Error string `json:"error"`
		}
		_ = json.Unmarshal(data, &e)
		if resp.StatusCode == http.StatusUnauthorized {
			return nil, errors.New("pairing refused (401): the code is wrong, expired or already used")
		}
		return nil, fmt.Errorf("pairing failed: server answered %d %s", resp.StatusCode, e.Error)
	}
	plain, err := sealed.Open(data)
	if err != nil {
		return nil, fmt.Errorf("pairing response rejected: %w", err)
	}
	var pr pairResponse
	if err := json.Unmarshal(plain, &pr); err != nil {
		return nil, fmt.Errorf("pairing response rejected: %w", err)
	}
	fp := Fingerprint(installationID, secret)
	if pr.Fingerprint != "" && !sameFingerprint(pr.Fingerprint, fp) {
		return nil, fmt.Errorf("fingerprint mismatch: server says %s, this installation is %s; not saving", pr.Fingerprint, fp)
	}
	return &config.Config{
		Server: server, Kid: req.Key.Kid, PublicKey: req.Key.PublicKey, TestOnly: req.Key.IsTest(),
		InstallationID: installationID, Secret: envelope.B64.EncodeToString(secret),
		DeviceID: pr.DeviceID, DeviceName: pr.DeviceName, Fingerprint: fp, PairedAt: req.Now().UnixMilli(),
	}, nil
}
