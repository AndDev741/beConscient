package upload_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/config"
	"github.com/AndDev741/beConscient/desktop/internal/envelope"
	"github.com/AndDev741/beConscient/desktop/internal/events"
	"github.com/AndDev741/beConscient/desktop/internal/health"
	"github.com/AndDev741/beConscient/desktop/internal/pairing"
	"github.com/AndDev741/beConscient/desktop/internal/queue"
	"github.com/AndDev741/beConscient/desktop/internal/testserver"
	"github.com/AndDev741/beConscient/desktop/internal/upload"
)

// The vector format of tools/cmd/vectorgen.
type vecEnvelope struct {
	V     int    `json:"v"`
	Kid   string `json:"kid,omitempty"`
	Enc   string `json:"enc,omitempty"`
	Ct    string `json:"ct"`
	Nonce string `json:"nonce,omitempty"`
}

type vec struct {
	Producer          string      `json:"producer"`
	Kid               string      `json:"kid"`
	Purpose           string      `json:"purpose"`
	Info              string      `json:"info"`
	Plaintext         string      `json:"plaintext"`
	Request           vecEnvelope `json:"request"`
	ResponseKey       string      `json:"response_key"`
	ResponsePlaintext string      `json:"response_plaintext"`
	Response          vecEnvelope `json:"response"`
}

func buildVector(t *testing.T, key *envelope.Key, info, purpose string, plaintext, response []byte) vec {
	t.Helper()
	sealed, err := envelope.Seal(key, info, plaintext)
	if err != nil {
		t.Fatal(err)
	}
	rk := sealed.ResponseKey()
	resp, err := testserver.SealResponse(rk, response)
	if err != nil {
		t.Fatal(err)
	}
	var r envelope.Response
	json.Unmarshal(resp, &r)
	// The agent itself must open what it wrote.
	if got, err := sealed.Open(resp); err != nil || string(got) != string(response) {
		t.Fatalf("own response does not open: %v", err)
	}
	return vec{
		Producer: "go-desktop", Kid: key.Kid, Purpose: purpose, Info: info, Plaintext: string(plaintext),
		Request:     vecEnvelope{V: 1, Kid: sealed.Request.Kid, Enc: sealed.Request.Enc, Ct: sealed.Request.Ct},
		ResponseKey: envelope.B64.EncodeToString(rk), ResponsePlaintext: string(response),
		Response: vecEnvelope{V: 1, Ct: r.Ct, Nonce: r.Nonce},
	}
}

// TestWriteDesktopVectors writes contract/vectors/go-desktop.json when
// BECONSCIENT_WRITE_VECTORS=1. The batch is frozen from a real queue with
// the agent's own event builders; the envelope uses the agent's sealing code.
func TestWriteDesktopVectors(t *testing.T) {
	if os.Getenv("BECONSCIENT_WRITE_VECTORS") != "1" {
		t.Skip("set BECONSCIENT_WRITE_VECTORS=1 to regenerate contract/vectors/go-desktop.json")
	}
	tk, _, err := testserver.LoadTestKey()
	if err != nil {
		t.Fatal(err)
	}
	key, err := envelope.NewKeyB64(tk.Kid, tk.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	secret := make([]byte, 32)
	for i := range secret {
		secret[i] = byte(i)
	}
	cfg := &config.Config{InstallationID: "0192f3a0-1c2b-7cde-8f00-112233445566", Secret: envelope.B64.EncodeToString(secret)}

	q, err := queue.Open(filepath.Join(t.TempDir(), "queue.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer q.Close()
	off := 60
	act := events.Activity{ID: "0192f3a1-0000-7000-8000-0000000000d1", Type: events.TypeActivity, Start: 1760083200000,
		End: 1760086800000, Rev: 3, Reason: events.ReasonInput, TZ: "Europe/Lisbon", TZOffsetMin: off}
	site := events.Site{ID: "0192f3a1-0000-7000-8000-0000000000d2", Type: events.TypeSite, Start: 1760087000000,
		End: 1760088200000, Rev: 2, Site: "youtube", Browser: "chrome", Playing: true, Title: "A video title",
		TZ: "Europe/Lisbon", TZOffsetMin: &off}
	now := time.UnixMilli(1760090000123)
	h := health.Build(health.Input{ID: "0192f3a1-0000-7000-8000-0000000000d3", Now: now.Add(-123 * time.Millisecond),
		TZ: "Europe/Lisbon", TZOffsetMin: off, ClientVersion: "0.0.0-test",
		Activity:   events.Sensor{State: events.StateOK, LastEventAt: 1760089995000},
		Heartbeats: map[string]queue.Heartbeat{"chrome": {Browser: "chrome", At: 1760089800000, Version: "0.1.0"}},
	})
	var evs []queue.Event
	for _, e := range []struct {
		id, typ string
		rev     int
		t0, t1  int64
		v       interface{}
	}{
		{act.ID, act.Type, act.Rev, act.Start, act.End, act},
		{site.ID, site.Type, site.Rev, site.Start, site.End, site},
		{h.ID, h.Type, 0, h.At, h.At, h},
	} {
		payload, _ := json.Marshal(e.v)
		evs = append(evs, queue.Event{ID: e.id, Type: e.typ, Rev: e.rev, T0: e.t0, T1: e.t1, Payload: payload})
	}
	if err := q.Put(evs...); err != nil {
		t.Fatal(err)
	}
	b, err := q.Freeze(cfg.InstallationID, upload.MaxEvents, upload.MaxBytes)
	if err != nil {
		t.Fatal(err)
	}
	batchPT, err := upload.BuildPlaintext(cfg, b, now, "Europe/Lisbon", 60)
	if err != nil {
		t.Fatal(err)
	}
	receipt, _ := json.Marshal(map[string]interface{}{
		"installation_id": cfg.InstallationID, "seq": b.Seq,
		"results": []map[string]string{
			{"id": act.ID, "status": "accepted"},
			{"id": site.ID, "status": "duplicate"},
			{"id": h.ID, "status": "accepted"},
		},
	})
	pairPT, err := pairing.BuildPlaintext("7KQ2-M9XD", cfg.InstallationID, secret, "Test desktop")
	if err != nil {
		t.Fatal(err)
	}
	pairResp, _ := json.Marshal(map[string]string{
		"device_id": "0192f3a0-0000-7000-8000-0000000000aa", "device_name": "Test desktop",
		"fingerprint": pairing.Fingerprint(cfg.InstallationID, secret),
	})
	vs := []vec{
		buildVector(t, key, envelope.InfoBatch, "batch", batchPT, receipt),
		buildVector(t, key, envelope.InfoPair, "pair", pairPT, pairResp),
	}
	dir, err := testserver.ContractDir()
	if err != nil {
		t.Fatal(err)
	}
	data, _ := json.MarshalIndent(vs, "", "  ")
	out := filepath.Join(dir, "vectors", "go-desktop.json")
	if err := os.WriteFile(out, append(data, '\n'), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Logf("wrote %s", out)
}
