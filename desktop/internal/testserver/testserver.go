// Package testserver is a fake beConscient ingest server for tests. It opens
// envelopes with the public test-only private key from contract/keys and
// follows the server rules of contract/envelope.md: credential check,
// sequence numbers, stored receipts for exact resends, revisions and
// duplicates.
package testserver

import (
	"crypto/ecdh"
	"crypto/hpke"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base32"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"golang.org/x/crypto/chacha20poly1305"

	"github.com/AndDev741/beConscient/desktop/internal/envelope"
)

// ContractDir finds the repository's contract/ directory from the current
// directory upward.
func ContractDir() (string, error) {
	dir, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		p := filepath.Join(dir, "contract")
		if _, err := os.Stat(filepath.Join(p, "keys", "test-keypair.json")); err == nil {
			return p, nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return "", errors.New("contract/ not found")
		}
		dir = parent
	}
}

// TestKey is the contract's test key pair.
type TestKey struct {
	Kid        string `json:"kid"`
	PublicKey  string `json:"public_key"`
	PrivateKey string `json:"private_key"`
	TestOnly   bool   `json:"test_only"`
}

// LoadTestKey reads contract/keys/test-keypair.json.
func LoadTestKey() (TestKey, hpke.PrivateKey, error) {
	var k TestKey
	dir, err := ContractDir()
	if err != nil {
		return k, nil, err
	}
	data, err := os.ReadFile(filepath.Join(dir, "keys", "test-keypair.json"))
	if err != nil {
		return k, nil, err
	}
	if err := json.Unmarshal(data, &k); err != nil {
		return k, nil, err
	}
	raw, err := envelope.B64.DecodeString(k.PrivateKey)
	if err != nil {
		return k, nil, err
	}
	ec, err := ecdh.X25519().NewPrivateKey(raw)
	if err != nil {
		return k, nil, err
	}
	sk, err := hpke.NewDHKEMPrivateKey(ec)
	return k, sk, err
}

// OpenRequest opens a request envelope as the server does and returns the
// plaintext and the response key.
func OpenRequest(sk hpke.PrivateKey, kid, info string, body []byte) ([]byte, []byte, error) {
	var req envelope.Request
	if err := json.Unmarshal(body, &req); err != nil {
		return nil, nil, err
	}
	if req.V != 1 || req.Kid != kid {
		return nil, nil, fmt.Errorf("bad envelope header v=%d kid=%q", req.V, req.Kid)
	}
	enc, err := envelope.B64.DecodeString(req.Enc)
	if err != nil {
		return nil, nil, err
	}
	ct, err := envelope.B64.DecodeString(req.Ct)
	if err != nil {
		return nil, nil, err
	}
	r, err := hpke.NewRecipient(enc, sk, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), []byte(info))
	if err != nil {
		return nil, nil, err
	}
	pt, err := r.Open([]byte(req.Kid), ct)
	if err != nil {
		return nil, nil, err
	}
	key, err := r.Export(envelope.ResponseContext, 32)
	return pt, key, err
}

// SealResponse encrypts a response body with an exported key.
func SealResponse(key, plaintext []byte) ([]byte, error) {
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, aead.NonceSize())
	rand.Read(nonce)
	ct := aead.Seal(nil, nonce, plaintext, []byte(envelope.ResponseContext))
	return json.Marshal(envelope.Response{V: 1, Nonce: envelope.B64.EncodeToString(nonce), Ct: envelope.B64.EncodeToString(ct)})
}

// Fingerprint computes the installation fingerprint from the contract text,
// independently of the agent's implementation.
func Fingerprint(installationID string, secret []byte) string {
	sh := sha256.Sum256(secret)
	sum := sha256.Sum256(append(append([]byte("beconscient-fp"), []byte(strings.ToLower(installationID))...), sh[:]...))
	enc := base32.NewEncoding("0123456789ABCDEFGHJKMNPQRSTVWXYZ").WithPadding(base32.NoPadding)
	s := enc.EncodeToString(sum[:])[:8]
	return s[:4] + "-" + s[4:]
}

// Header is the batch header as the server sees it.
type Header struct {
	InstallationID  string `json:"installation_id"`
	Credential      string `json:"credential"`
	Seq             int64  `json:"seq"`
	SentAt          int64  `json:"sent_at"`
	TZ              string `json:"tz"`
	TZOffsetMin     int    `json:"tz_offset_min"`
	ContractVersion int    `json:"contract_version"`
	ClientVersion   string `json:"client_version"`
}

// Batch is one received batch request.
type Batch struct {
	Header    Header
	Events    []json.RawMessage
	Plaintext []byte
}

// IDRevs lists "id/rev" of the batch, in order.
func (b Batch) IDRevs() []string {
	var out []string
	for _, raw := range b.Events {
		var e struct {
			ID  string `json:"id"`
			Rev int    `json:"rev"`
		}
		json.Unmarshal(raw, &e)
		out = append(out, fmt.Sprintf("%s/%d", e.ID, e.Rev))
	}
	return out
}

type result struct {
	ID     string `json:"id"`
	Status string `json:"status"`
	Reason string `json:"reason,omitempty"`
}

type logged struct {
	list    string
	receipt []byte
}

// Server is the fake ingest server.
type Server struct {
	*httptest.Server
	Key TestKey
	sk  hpke.PrivateKey

	mu      sync.Mutex
	Batches []Batch
	Pairs   []map[string]string
	secrets map[string]string // installation id -> credential
	lastSeq map[string]int64
	logs    map[string]map[int64]logged
	revs    map[string]int // event id -> highest stored rev
	// Reject maps an event id to a rejection reason.
	Reject map[string]string
	// LoseResponses makes the next n batch responses get lost after the
	// batch was durably processed.
	LoseResponses int
	// FailStatus answers the next n batch requests with this status
	// without processing them.
	FailStatus int
	FailCount  int
	// Forge answers 200 with a response sealed under a random key.
	Forge bool
	// ExtraResults adds results for ids that were not in the batch.
	ExtraResults []string
	// PairCodes are the open pairing codes.
	PairCodes map[string]bool
}

// New starts a fake server.
func New() (*Server, error) {
	k, sk, err := LoadTestKey()
	if err != nil {
		return nil, err
	}
	s := &Server{Key: k, sk: sk, secrets: map[string]string{}, lastSeq: map[string]int64{},
		logs: map[string]map[int64]logged{}, revs: map[string]int{}, Reject: map[string]string{},
		PairCodes: map[string]bool{}}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /ingest/v1/batches", s.batch)
	mux.HandleFunc("POST /ingest/v1/pair", s.pair)
	s.Server = httptest.NewServer(mux)
	return s, nil
}

// AddInstallation registers an installation's credential.
func (s *Server) AddInstallation(id, credential string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.secrets[id] = credential
}

// Stored returns the highest stored revision per event id.
func (s *Server) Stored() map[string]int {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := map[string]int{}
	for k, v := range s.revs {
		out[k] = v
	}
	return out
}

// Received returns a copy of the received batches.
func (s *Server) Received() []Batch {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Batch(nil), s.Batches...)
}

func fail(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	fmt.Fprintf(w, `{"error":%q}`, code)
}

func (s *Server) batch(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(io.LimitReader(r.Body, envelope.MaxBody+1))
	if err != nil || len(body) > envelope.MaxBody {
		fail(w, http.StatusRequestEntityTooLarge, "too_large")
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.FailCount > 0 {
		s.FailCount--
		fail(w, s.FailStatus, "unavailable")
		return
	}
	pt, key, err := OpenRequest(s.sk, s.Key.Kid, envelope.InfoBatch, body)
	if err != nil {
		fail(w, http.StatusBadRequest, "bad_envelope")
		return
	}
	var b struct {
		Header Header            `json:"header"`
		Events []json.RawMessage `json:"events"`
	}
	if err := json.Unmarshal(pt, &b); err != nil {
		fail(w, http.StatusBadRequest, "bad_envelope")
		return
	}
	if cred, ok := s.secrets[b.Header.InstallationID]; !ok || cred != b.Header.Credential {
		fail(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if len(b.Events) > 2000 {
		fail(w, http.StatusRequestEntityTooLarge, "too_large")
		return
	}
	batch := Batch{Header: b.Header, Events: b.Events, Plaintext: pt}
	s.Batches = append(s.Batches, batch)
	list := strings.Join(batch.IDRevs(), ",")
	inst := b.Header.InstallationID
	if s.logs[inst] == nil {
		s.logs[inst] = map[int64]logged{}
	}
	var receipt []byte
	if prev, ok := s.logs[inst][b.Header.Seq]; ok && prev.list == list {
		receipt = prev.receipt // exact resend: the stored receipt again
	} else if b.Header.Seq <= s.lastSeq[inst] {
		fail(w, http.StatusConflict, "sequence_conflict")
		return
	} else {
		var results []result
		for _, raw := range b.Events {
			var e struct {
				ID  string `json:"id"`
				Rev int    `json:"rev"`
			}
			json.Unmarshal(raw, &e)
			switch {
			case s.Reject[e.ID] != "":
				results = append(results, result{ID: e.ID, Status: "rejected", Reason: s.Reject[e.ID]})
			case func() bool { r, ok := s.revs[e.ID]; return ok && r >= e.Rev }():
				results = append(results, result{ID: e.ID, Status: "duplicate"})
			default:
				s.revs[e.ID] = e.Rev
				results = append(results, result{ID: e.ID, Status: "accepted"})
			}
		}
		for _, id := range s.ExtraResults {
			results = append(results, result{ID: id, Status: "accepted"})
		}
		receipt, _ = json.Marshal(map[string]interface{}{"installation_id": inst, "seq": b.Header.Seq, "results": results})
		s.logs[inst][b.Header.Seq] = logged{list: list, receipt: receipt}
		s.lastSeq[inst] = b.Header.Seq
	}
	if s.LoseResponses > 0 {
		s.LoseResponses--
		hj, ok := w.(http.Hijacker)
		if ok {
			if c, _, err := hj.Hijack(); err == nil {
				c.Close()
				return
			}
		}
		fail(w, http.StatusBadGateway, "unavailable")
		return
	}
	if s.Forge {
		key = make([]byte, 32)
		rand.Read(key)
	}
	out, _ := SealResponse(key, receipt)
	w.Header().Set("Content-Type", "application/json")
	w.Write(out)
}

func (s *Server) pair(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(io.LimitReader(r.Body, envelope.MaxBody))
	s.mu.Lock()
	defer s.mu.Unlock()
	pt, key, err := OpenRequest(s.sk, s.Key.Kid, envelope.InfoPair, body)
	if err != nil {
		fail(w, http.StatusBadRequest, "bad_envelope")
		return
	}
	var p map[string]string
	if err := json.Unmarshal(pt, &p); err != nil {
		fail(w, http.StatusBadRequest, "bad_envelope")
		return
	}
	if !s.PairCodes[p["code"]] {
		fail(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	delete(s.PairCodes, p["code"])
	s.Pairs = append(s.Pairs, p)
	secret, _ := envelope.B64.DecodeString(p["secret"])
	s.secrets[p["installation_id"]] = p["secret"]
	resp, _ := json.Marshal(map[string]string{
		"device_id": "0192f3a0-0000-7000-8000-0000000000aa", "device_name": p["name"],
		"fingerprint": Fingerprint(p["installation_id"], secret),
	})
	out, _ := SealResponse(key, resp)
	w.Header().Set("Content-Type", "application/json")
	w.Write(out)
}
