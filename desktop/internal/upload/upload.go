// Package upload sends frozen batches to the server inside HPKE envelopes and
// applies the encrypted receipts (contract/envelope.md).
package upload

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"os"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/config"
	"github.com/AndDev741/beConscient/desktop/internal/envelope"
	"github.com/AndDev741/beConscient/desktop/internal/queue"
	"github.com/AndDev741/beConscient/desktop/internal/tz"
	"github.com/AndDev741/beConscient/desktop/internal/version"
)

// Limits from the contract.
const (
	MaxEvents = 2000
	// MaxBytes keeps the sealed body (JSON, base64, header) under 1 MiB.
	MaxBytes = 700 << 10
	// minBytes is the floor after repeated 413s.
	minBytes = 32 << 10
	// maxResponse caps how much of a response body is read.
	maxResponse = 4 << 20
	// BatchPath is the batch endpoint below the server URL.
	BatchPath = "/ingest/v1/batches"
	// MetaKey stores the last upload status for `status`.
	MetaKey = "upload"
)

// Backoff limits.
const (
	BackoffBase = 30 * time.Second
	BackoffMax  = 30 * time.Minute
	// Interval is the normal pause between upload cycles.
	Interval = time.Minute
)

// ErrNotPaired means there is no agent.json yet.
var ErrNotPaired = errors.New("not paired")

// HTTPError is a non-200 answer from the server.
type HTTPError struct {
	Status int
	Code   string
}

func (e *HTTPError) Error() string {
	if e.Code != "" {
		return fmt.Sprintf("server answered %d %s", e.Status, e.Code)
	}
	return fmt.Sprintf("server answered %d", e.Status)
}

// Uploader sends the queue to the server.
type Uploader struct {
	Q          *queue.Queue
	LoadConfig func() (*config.Config, error)
	HTTP       *http.Client
	Now        func() time.Time
	TZ         tz.Func
	Log        *slog.Logger
	// Dev allows the test-only key.
	Dev bool

	MaxEvents int
	MaxBytes  int

	failures int
}

// Status is stored in the queue meta table after every attempt.
type Status struct {
	LastAttemptAt int64  `json:"last_attempt_at,omitempty"`
	LastSuccessAt int64  `json:"last_success_at,omitempty"`
	LastSeq       int64  `json:"last_seq,omitempty"`
	LastError     string `json:"last_error,omitempty"`
	Failures      int    `json:"failures"`
	NextAttemptAt int64  `json:"next_attempt_at,omitempty"`
}

// Result describes one upload cycle.
type Result struct {
	Sent    bool
	Seq     int64
	Events  int
	Summary queue.ReceiptSummary
}

type header struct {
	InstallationID  string `json:"installation_id"`
	Credential      string `json:"credential"`
	Seq             int64  `json:"seq"`
	SentAt          int64  `json:"sent_at"`
	TZ              string `json:"tz"`
	TZOffsetMin     int    `json:"tz_offset_min"`
	ContractVersion int    `json:"contract_version"`
	ClientVersion   string `json:"client_version"`
}

type plaintext struct {
	Header header          `json:"header"`
	Events json.RawMessage `json:"events"`
}

type receipt struct {
	InstallationID string         `json:"installation_id"`
	Seq            int64          `json:"seq"`
	Results        []queue.Result `json:"results"`
}

func (u *Uploader) defaults() {
	if u.HTTP == nil {
		u.HTTP = &http.Client{Timeout: 60 * time.Second}
	}
	if u.Now == nil {
		u.Now = time.Now
	}
	if u.TZ == nil {
		u.TZ = tz.Local
	}
	if u.Log == nil {
		u.Log = slog.Default()
	}
	if u.MaxEvents <= 0 || u.MaxEvents > MaxEvents {
		u.MaxEvents = MaxEvents
	}
	if u.MaxBytes <= 0 || u.MaxBytes > MaxBytes {
		u.MaxBytes = MaxBytes
	}
}

// Once freezes a batch if none is frozen, sends the frozen batch and applies
// a valid receipt. Nothing is acknowledged unless the response opens with
// this request's exported key and names this installation and sequence.
func (u *Uploader) Once(ctx context.Context) (Result, error) {
	u.defaults()
	cfg, err := u.LoadConfig()
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return Result{}, ErrNotPaired
		}
		return Result{}, err
	}
	if err := config.CheckKey(cfg.Key(), u.Dev); err != nil {
		return Result{}, err
	}
	key, err := cfg.EnvelopeKey()
	if err != nil {
		return Result{}, err
	}
	b, err := u.Q.Freeze(cfg.InstallationID, u.MaxEvents, u.MaxBytes)
	if err != nil || b == nil {
		return Result{}, err
	}
	res := Result{Seq: b.Seq, Events: len(b.Items)}

	now := u.Now()
	zone, off := u.TZ(now)
	pt, err := BuildPlaintext(cfg, b, now, zone, off)
	if err != nil {
		return res, err
	}
	sealed, err := envelope.Seal(key, envelope.InfoBatch, pt)
	if err != nil {
		return res, err
	}
	body, err := sealed.Body()
	if err != nil {
		return res, err
	}
	if len(body) > envelope.MaxBody {
		u.shrink()
		if err := u.Q.DropFrozen(b); err != nil {
			return res, err
		}
		return res, fmt.Errorf("batch %d is %d bytes sealed; refreezing smaller", b.Seq, len(body))
	}
	if err := u.Q.MarkAttempt(b); err != nil {
		return res, err
	}
	status, respBody, err := u.post(ctx, cfg.Server+BatchPath, body)
	if err != nil {
		return res, err
	}
	switch status {
	case http.StatusOK:
	case http.StatusRequestEntityTooLarge:
		// The server never logged this batch: refreeze with fewer events.
		u.shrink()
		if err := u.Q.DropFrozen(b); err != nil {
			return res, err
		}
		return res, &HTTPError{Status: status, Code: errorCode(respBody)}
	default:
		return res, &HTTPError{Status: status, Code: errorCode(respBody)}
	}
	plain, err := sealed.Open(respBody)
	if err != nil {
		return res, fmt.Errorf("receipt rejected: %w", err)
	}
	var rc receipt
	if err := json.Unmarshal(plain, &rc); err != nil {
		return res, fmt.Errorf("receipt rejected: %w", err)
	}
	if rc.InstallationID != cfg.InstallationID || rc.Seq != b.Seq {
		return res, fmt.Errorf("receipt rejected: it answers %s/%d, not %s/%d", rc.InstallationID, rc.Seq, cfg.InstallationID, b.Seq)
	}
	sum, err := u.Q.ApplyReceipt(b, rc.Results)
	if err != nil {
		return res, err
	}
	res.Sent, res.Summary = true, sum
	if sum.Rejected > 0 {
		u.Log.Warn("server rejected events; they are kept in the local rejected table", "seq", b.Seq, "rejected", sum.Rejected)
	}
	return res, nil
}

// BuildPlaintext returns the batch plaintext: the header (with the
// credential, sequence number and send time) and the frozen events array,
// byte for byte as frozen.
func BuildPlaintext(cfg *config.Config, b *queue.Batch, now time.Time, zone string, offsetMin int) ([]byte, error) {
	return json.Marshal(plaintext{
		Header: header{
			InstallationID: cfg.InstallationID, Credential: cfg.Secret, Seq: b.Seq, SentAt: now.UnixMilli(),
			TZ: zone, TZOffsetMin: offsetMin, ContractVersion: version.ContractVersion, ClientVersion: version.Version,
		},
		Events: b.Events,
	})
}

func (u *Uploader) shrink() {
	u.MaxBytes = max(u.MaxBytes/2, minBytes)
	u.MaxEvents = max(u.MaxEvents/2, 1)
}

func (u *Uploader) post(ctx context.Context, url string, body []byte) (int, []byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "beconscient-agent/"+version.Version)
	resp, err := u.HTTP.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxResponse))
	if err != nil {
		return resp.StatusCode, nil, err
	}
	return resp.StatusCode, data, nil
}

func errorCode(body []byte) string {
	var e struct {
		Error string `json:"error"`
	}
	if json.Unmarshal(body, &e) == nil && len(e.Error) <= 64 {
		return e.Error
	}
	return ""
}

// Backoff returns the wait after n consecutive failures: 30 s doubling up to
// 30 minutes, with up to 20% jitter either way.
func Backoff(n int) time.Duration {
	if n < 1 {
		n = 1
	}
	d := BackoffBase
	for i := 1; i < n && d < BackoffMax; i++ {
		d *= 2
	}
	if d > BackoffMax {
		d = BackoffMax
	}
	jitter := time.Duration((rand.Float64()*0.4 - 0.2) * float64(d))
	return d + jitter
}

// Run uploads every minute while there is something to send, backing off
// exponentially on failure, until ctx is done.
func (u *Uploader) Run(ctx context.Context) {
	u.defaults()
	var st Status
	_, _ = u.Q.MetaJSON(MetaKey, &st)
	st.Failures = 0
	notPairedLogged := false
	wait := 5 * time.Second
	for {
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
		wait = Interval
		for i := 0; i < 20; i++ {
			res, err := u.Once(ctx)
			now := u.Now()
			switch {
			case errors.Is(err, ErrNotPaired):
				if !notPairedLogged {
					u.Log.Warn("not paired yet: events stay in the queue until `beconscient-agent pair`")
					notPairedLogged = true
				}
			case err != nil:
				u.failures++
				wait = Backoff(u.failures)
				st.LastAttemptAt, st.LastError, st.Failures = now.UnixMilli(), err.Error(), u.failures
				st.NextAttemptAt = now.Add(wait).UnixMilli()
				var he *HTTPError
				if errors.As(err, &he) && (he.Status == http.StatusConflict || he.Status == http.StatusUnauthorized) {
					u.Log.Error("upload refused; re-pair this installation if it persists", "err", err, "retry_in", wait.Round(time.Second).String())
				} else {
					u.Log.Warn("upload failed", "err", err, "retry_in", wait.Round(time.Second).String())
				}
			case res.Sent:
				u.failures = 0
				st = Status{LastAttemptAt: now.UnixMilli(), LastSuccessAt: now.UnixMilli(), LastSeq: res.Seq}
				u.Log.Info("batch acknowledged", "seq", res.Seq, "events", res.Events,
					"accepted", res.Summary.Accepted, "duplicate", res.Summary.Duplicate, "rejected", res.Summary.Rejected)
			}
			if err == nil && res.Sent {
				notPairedLogged = false
				_ = u.Q.SetMetaJSON(MetaKey, st)
				continue // more may be pending
			}
			if err != nil && !errors.Is(err, ErrNotPaired) {
				_ = u.Q.SetMetaJSON(MetaKey, st)
			}
			break
		}
	}
}
