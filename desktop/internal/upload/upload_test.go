package upload_test

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/config"
	"github.com/AndDev741/beConscient/desktop/internal/envelope"
	"github.com/AndDev741/beConscient/desktop/internal/queue"
	"github.com/AndDev741/beConscient/desktop/internal/testserver"
	"github.com/AndDev741/beConscient/desktop/internal/tz"
	"github.com/AndDev741/beConscient/desktop/internal/upload"
)

const installation = "0192f3a0-1c2b-7cde-8f00-112233445566"

var credential = envelope.B64.EncodeToString(make([]byte, 32))

type env struct {
	q   *queue.Queue
	srv *testserver.Server
	up  *upload.Uploader
	cfg *config.Config
}

func setup(t *testing.T) *env {
	t.Helper()
	q, err := queue.Open(filepath.Join(t.TempDir(), "queue.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { q.Close() })
	srv, err := testserver.New()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(srv.Close)
	srv.AddInstallation(installation, credential)
	cfg := &config.Config{Server: srv.URL, Kid: srv.Key.Kid, PublicKey: srv.Key.PublicKey, TestOnly: true,
		InstallationID: installation, Secret: credential}
	up := &upload.Uploader{Q: q, LoadConfig: func() (*config.Config, error) { return cfg, nil }, Dev: true,
		TZ: tz.Fixed("Europe/Lisbon", 60)}
	return &env{q: q, srv: srv, up: up, cfg: cfg}
}

func iv(id string, rev int, start int64) queue.Event {
	p := fmt.Sprintf(`{"id":%q,"type":"desktop.activity","start":%d,"end":%d,"rev":%d,"reason":"input","tz_offset_min":60}`, id, start, start+1000*int64(rev), rev)
	return queue.Event{ID: id, Rev: rev, Type: "desktop.activity", Tag: "input", T0: start, T1: start + 1000*int64(rev), Payload: []byte(p)}
}

func (e *env) once(t *testing.T) upload.Result {
	t.Helper()
	res, err := e.up.Once(context.Background())
	if err != nil {
		t.Fatalf("upload: %v", err)
	}
	return res
}

func TestUploadAndAcknowledge(t *testing.T) {
	e := setup(t)
	e.q.Put(iv("0192f3a1-0000-7000-8000-000000000001", 1, 1760083200000), iv("0192f3a1-0000-7000-8000-000000000002", 3, 1760083300000))
	res := e.once(t)
	if !res.Sent || res.Seq != 1 || res.Summary.Accepted != 2 {
		t.Fatalf("result %+v", res)
	}
	b := e.srv.Received()[0]
	h := b.Header
	if h.InstallationID != installation || h.Credential != credential || h.Seq != 1 || h.ContractVersion != 1 ||
		h.ClientVersion == "" || h.TZ != "Europe/Lisbon" || h.TZOffsetMin != 60 || h.SentAt == 0 {
		t.Fatalf("header %+v", h)
	}
	st, _ := e.q.Stats()
	if st.Pending != 0 || st.Acked != 2 || st.Frozen != nil {
		t.Fatalf("stats %+v", st)
	}
	if res := e.once(t); res.Sent {
		t.Fatal("nothing should be left to send")
	}
}

func TestFrozenBatchResentUnchangedAfterLostResponse(t *testing.T) {
	e := setup(t)
	a, b := "0192f3a1-0000-7000-8000-00000000000a", "0192f3a1-0000-7000-8000-00000000000b"
	e.q.Put(iv(a, 1, 1000), iv(b, 1, 2000))
	e.srv.LoseResponses = 1
	if _, err := e.up.Once(context.Background()); err == nil {
		t.Fatal("a lost response must be an error")
	}
	if r, _, _ := e.q.AckedRev(a); r != -1 {
		t.Fatal("acknowledged without a receipt")
	}
	// New revisions arrive before the resend.
	e.q.Put(iv(a, 2, 1000), iv("0192f3a1-0000-7000-8000-00000000000c", 1, 3000))
	res := e.once(t)
	got := e.srv.Received()
	if len(got) != 2 || got[0].Header.Seq != got[1].Header.Seq ||
		strings.Join(got[0].IDRevs(), ",") != strings.Join(got[1].IDRevs(), ",") {
		t.Fatalf("resend differs: %v vs %v", got[0].IDRevs(), got[1].IDRevs())
	}
	// The server returned the stored receipt: both accepted the first time.
	if res.Summary.Accepted != 2 {
		t.Fatalf("summary %+v", res.Summary)
	}
	res = e.once(t)
	last := e.srv.Received()[2]
	if res.Seq != 2 || strings.Join(last.IDRevs(), ",") != a+"/2,0192f3a1-0000-7000-8000-00000000000c/1" {
		t.Fatalf("next batch seq %d: %v", res.Seq, last.IDRevs())
	}
	if e.srv.Stored()[a] != 2 {
		t.Fatal("revision 2 not stored")
	}
}

func TestForgedResponseAcknowledgesNothing(t *testing.T) {
	e := setup(t)
	id := "0192f3a1-0000-7000-8000-000000000001"
	e.q.Put(iv(id, 1, 1000))
	e.srv.Forge = true
	if _, err := e.up.Once(context.Background()); err == nil || !strings.Contains(err.Error(), "receipt rejected") {
		t.Fatalf("forged response accepted: %v", err)
	}
	st, _ := e.q.Stats()
	if st.Pending != 1 || st.Frozen == nil {
		t.Fatalf("stats %+v", st)
	}
	e.srv.Forge = false
	if res := e.once(t); res.Seq != 1 || res.Summary.Accepted+res.Summary.Duplicate != 1 {
		t.Fatalf("resend %+v", res)
	}
}

func TestOnlyIdsOfTheBatchAreActedOn(t *testing.T) {
	e := setup(t)
	inBatch, outside := "0192f3a1-0000-7000-8000-000000000001", "0192f3a1-0000-7000-8000-000000000099"
	e.q.Put(iv(inBatch, 1, 1000))
	e.up.MaxEvents = 1
	e.q.Put(iv(outside, 1, 9000)) // queued, but not in the first batch
	e.srv.ExtraResults = []string{outside}
	res := e.once(t)
	if res.Summary.Ignored != 1 {
		t.Fatalf("summary %+v", res.Summary)
	}
	if r, _, _ := e.q.AckedRev(outside); r != -1 {
		t.Fatal("an id outside the batch was acknowledged")
	}
}

func TestRejectedGoToRejectedTable(t *testing.T) {
	e := setup(t)
	bad := "0192f3a1-0000-7000-8000-000000000005"
	e.q.Put(iv(bad, 1, 1000), iv("0192f3a1-0000-7000-8000-000000000006", 1, 2000))
	e.srv.Reject[bad] = "end_before_start"
	res := e.once(t)
	if res.Summary.Rejected != 1 || res.Summary.Accepted != 1 {
		t.Fatalf("summary %+v", res.Summary)
	}
	rej, _ := e.q.Rejected()
	if len(rej) != 1 || rej[0].ID != bad || rej[0].Reason != "end_before_start" {
		t.Fatalf("rejected %+v", rej)
	}
	if res := e.once(t); res.Sent {
		t.Fatal("a rejected event was resent")
	}
}

func TestReplayResendsAcknowledgedAndToleratesDuplicates(t *testing.T) {
	e := setup(t)
	ids := []string{"0192f3a1-0000-7000-8000-000000000001", "0192f3a1-0000-7000-8000-000000000002"}
	e.q.Put(iv(ids[0], 1, 1760083200000), iv(ids[1], 1, 1760083300000))
	e.once(t)
	n, err := e.q.Replay(time.UnixMilli(0))
	if err != nil || n != 2 {
		t.Fatalf("replay %d %v", n, err)
	}
	res := e.once(t)
	if !res.Sent || res.Summary.Duplicate != 2 || res.Seq != 2 {
		t.Fatalf("replay upload %+v", res)
	}
	st, _ := e.q.Stats()
	if st.Pending != 0 || st.Acked != 2 {
		t.Fatalf("stats %+v", st)
	}
}

func TestServerErrorsKeepTheBatchFrozen(t *testing.T) {
	e := setup(t)
	e.q.Put(iv("0192f3a1-0000-7000-8000-000000000001", 1, 1000))
	e.srv.FailStatus, e.srv.FailCount = 503, 2
	for i := 0; i < 2; i++ {
		_, err := e.up.Once(context.Background())
		var he *upload.HTTPError
		if !errors.As(err, &he) || he.Status != 503 || he.Code != "unavailable" {
			t.Fatalf("attempt %d: %v", i, err)
		}
	}
	st, _ := e.q.Stats()
	if st.Frozen == nil || st.Frozen.Seq != 1 || st.Frozen.Attempts != 2 {
		t.Fatalf("frozen %+v", st.Frozen)
	}
	if res := e.once(t); res.Seq != 1 {
		t.Fatalf("after recovery seq %d, want the same frozen 1", res.Seq)
	}
}

func TestBackoffIsExponentialAndCapped(t *testing.T) {
	prev := time.Duration(0)
	for n := 1; n <= 8; n++ {
		d := upload.Backoff(n)
		base := upload.BackoffBase << (n - 1)
		if base > upload.BackoffMax {
			base = upload.BackoffMax
		}
		if d < base*8/10 || d > base*12/10 {
			t.Fatalf("backoff(%d) = %v, want about %v", n, d, base)
		}
		if n <= 4 && d <= prev {
			t.Fatalf("backoff(%d) = %v did not grow from %v", n, d, prev)
		}
		prev = d
	}
	if d := upload.Backoff(50); d > upload.BackoffMax*12/10 {
		t.Fatalf("backoff not capped: %v", d)
	}
}

func TestDefaultRefusesTestKey(t *testing.T) {
	e := setup(t)
	e.q.Put(iv("0192f3a1-0000-7000-8000-000000000001", 1, 1000))
	e.up.Dev = false
	if _, err := e.up.Once(context.Background()); !errors.Is(err, config.ErrTestKey) {
		t.Fatalf("got %v, want the test key refused", err)
	}
	if len(e.srv.Received()) != 0 {
		t.Fatal("something was sent under the test key")
	}
	// Even with test_only stripped, the key bytes give it away.
	e.cfg.TestOnly = false
	e.cfg.Kid = "prod-2026-10"
	if _, err := e.up.Once(context.Background()); !errors.Is(err, config.ErrTestKey) {
		t.Fatalf("got %v, want the test key refused by its bytes", err)
	}
}

func TestAtMost2000EventsPerBatch(t *testing.T) {
	e := setup(t)
	var evs []queue.Event
	for i := 0; i < 2500; i++ {
		evs = append(evs, iv(fmt.Sprintf("0192f3a1-0000-7000-8000-%012d", i), 1, int64(1000+i)))
	}
	e.q.Put(evs...)
	r1 := e.once(t)
	r2 := e.once(t)
	if r1.Events != 2000 || r2.Events != 500 || r1.Seq != 1 || r2.Seq != 2 {
		t.Fatalf("batches %+v %+v", r1, r2)
	}
	for _, b := range e.srv.Received() {
		if len(b.Events) > 2000 {
			t.Fatalf("batch of %d events", len(b.Events))
		}
	}
}

func TestTooLargeRefreezesSmaller(t *testing.T) {
	e := setup(t)
	e.q.Put(iv("0192f3a1-0000-7000-8000-000000000001", 1, 1000), iv("0192f3a1-0000-7000-8000-000000000002", 1, 2000))
	e.srv.FailStatus, e.srv.FailCount = 413, 1
	if _, err := e.up.Once(context.Background()); err == nil {
		t.Fatal("413 must be an error")
	}
	st, _ := e.q.Stats()
	if st.Frozen != nil {
		t.Fatal("a batch the server never logged must be dropped after 413")
	}
	res := e.once(t)
	if res.Seq != 2 || e.up.MaxEvents != upload.MaxEvents/2 || e.up.MaxBytes != upload.MaxBytes/2 {
		t.Fatalf("after 413: %+v (max %d events, %d bytes), want smaller limits and a new seq", res, e.up.MaxEvents, e.up.MaxBytes)
	}
}

func TestNotPaired(t *testing.T) {
	e := setup(t)
	e.up.LoadConfig = func() (*config.Config, error) { return nil, fmt.Errorf("open: %w", os.ErrNotExist) }
	if _, err := e.up.Once(context.Background()); !errors.Is(err, upload.ErrNotPaired) {
		t.Fatalf("got %v", err)
	}
}
