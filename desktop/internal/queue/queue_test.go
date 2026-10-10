package queue_test

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/queue"
)

var t0 = time.Date(2026, 10, 10, 9, 0, 0, 0, time.UTC)

func open(t *testing.T) (*queue.Queue, *time.Time) {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "state", "beconscient")
	q, err := queue.Open(filepath.Join(dir, "queue.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { q.Close() })
	now := t0
	q.SetNow(func() time.Time { return now })
	return q, &now
}

func interval(id string, rev int, start, end int64) queue.Event {
	payload := fmt.Sprintf(`{"id":%q,"type":"desktop.activity","start":%d,"end":%d,"rev":%d,"reason":"input","tz_offset_min":0}`, id, start, end, rev)
	return queue.Event{ID: id, Rev: rev, Type: "desktop.activity", Tag: "input", T0: start, T1: end, Payload: []byte(payload)}
}

func TestFilesArePrivate(t *testing.T) {
	base := t.TempDir()
	dir := filepath.Join(base, "state", "beconscient")
	q, err := queue.Open(filepath.Join(dir, "queue.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer q.Close()
	if err := q.Put(interval("a", 1, 1, 2)); err != nil {
		t.Fatal(err)
	}
	fi, err := os.Stat(dir)
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode().Perm() != 0o700 {
		t.Fatalf("state dir mode %o, want 700", fi.Mode().Perm())
	}
	entries, _ := os.ReadDir(dir)
	names := map[string]bool{}
	for _, e := range entries {
		names[e.Name()] = true
	}
	if !names["queue.db"] || !names["queue.db-wal"] || !names["queue.db-shm"] {
		t.Fatalf("expected the database and its WAL files, got %v", names)
	}
	for _, e := range entries {
		fi, _ := e.Info()
		if fi.Mode().Perm() != 0o600 {
			t.Fatalf("%s mode %o, want 600", e.Name(), fi.Mode().Perm())
		}
	}

	// An existing directory with loose permissions is tightened.
	loose := filepath.Join(base, "loose")
	os.MkdirAll(loose, 0o755)
	os.WriteFile(filepath.Join(loose, "queue.db"), nil, 0o644)
	q2, err := queue.Open(filepath.Join(loose, "queue.db"))
	if err != nil {
		t.Fatal(err)
	}
	q2.Close()
	fi, _ = os.Stat(loose)
	fi2, _ := os.Stat(filepath.Join(loose, "queue.db"))
	if fi.Mode().Perm() != 0o700 || fi2.Mode().Perm() != 0o600 {
		t.Fatalf("modes %o %o, want 700 600", fi.Mode().Perm(), fi2.Mode().Perm())
	}
}

func TestRevisionsReplaceOnlyUpward(t *testing.T) {
	q, _ := open(t)
	must(t, q.Put(interval("a", 2, 100, 300)))
	must(t, q.Put(interval("a", 1, 100, 200))) // older: ignored
	must(t, q.Put(interval("a", 2, 100, 999))) // equal: ignored
	ev, _, _ := q.Get("a")
	if ev.Rev != 2 || ev.T1 != 300 {
		t.Fatalf("stored %+v, want rev 2 ending 300", ev)
	}
	must(t, q.Put(interval("a", 3, 100, 150))) // newer, shorter: replaces
	ev, _, _ = q.Get("a")
	if ev.Rev != 3 || ev.T1 != 150 {
		t.Fatalf("stored %+v, want rev 3 ending 150", ev)
	}
}

func TestFrozenBatchIsResentUnchanged(t *testing.T) {
	q, _ := open(t)
	must(t, q.Put(interval("a", 1, 100, 200), interval("b", 1, 300, 400)))
	b1, err := q.Freeze("inst", 2000, 1<<20)
	must(t, err)
	if b1 == nil || b1.Seq != 1 || len(b1.Items) != 2 {
		t.Fatalf("first batch %+v", b1)
	}
	// New revisions and new events arrive while the batch is in flight.
	must(t, q.Put(interval("a", 2, 100, 250), interval("c", 1, 500, 600)))
	b2, err := q.Freeze("inst", 2000, 1<<20)
	must(t, err)
	if b2.Seq != b1.Seq || string(b2.Events) != string(b1.Events) || fmt.Sprint(b2.Items) != fmt.Sprint(b1.Items) {
		t.Fatalf("resend changed the frozen batch:\n%s\n%s", b1.Events, b2.Events)
	}

	sum, err := q.ApplyReceipt(b2, []queue.Result{{ID: "a", Status: "accepted"}, {ID: "b", Status: "duplicate"}})
	must(t, err)
	if sum.Accepted != 1 || sum.Duplicate != 1 {
		t.Fatalf("summary %+v", sum)
	}
	// a rev 2 and c are still pending; b is done.
	b3, err := q.Freeze("inst", 2000, 1<<20)
	must(t, err)
	if b3.Seq != 2 || fmt.Sprint(b3.Items) != fmt.Sprint([]queue.Item{{ID: "a", Rev: 2}, {ID: "c", Rev: 1}}) {
		t.Fatalf("next batch %+v", b3)
	}
}

func TestReceiptActsOnlyOnIdsOfThatBatch(t *testing.T) {
	q, _ := open(t)
	must(t, q.Put(interval("a", 1, 100, 200)))
	b, err := q.Freeze("inst", 2000, 1<<20)
	must(t, err)
	must(t, q.Put(interval("z", 1, 300, 400)))
	sum, err := q.ApplyReceipt(b, []queue.Result{
		{ID: "z", Status: "accepted"},                      // not in this batch
		{ID: "a", Status: "rejected", Reason: "whatever"},  // in the batch
		{ID: "a", Status: "accepted"},                      // repeated id: ignored
		{ID: "a", Status: "rejected", Reason: "something"}, // repeated id: ignored
	})
	must(t, err)
	if sum.Rejected != 1 || sum.Ignored != 3 || sum.Accepted != 0 {
		t.Fatalf("summary %+v", sum)
	}
	if r, _, _ := q.AckedRev("z"); r != -1 {
		t.Fatal("an id outside the batch was acknowledged")
	}
	if _, ok, _ := q.Get("a"); ok {
		t.Fatal("rejected event still queued")
	}
	rej, _ := q.Rejected()
	if len(rej) != 1 || rej[0].ID != "a" || rej[0].Reason != "whatever" {
		t.Fatalf("rejected table %+v", rej)
	}
	// Rejected events are never resent.
	b2, _ := q.Freeze("inst", 2000, 1<<20)
	if len(b2.Items) != 1 || b2.Items[0].ID != "z" {
		t.Fatalf("next batch %+v", b2.Items)
	}
	// A receipt for a batch that is not frozen anymore changes nothing.
	if _, err := q.ApplyReceipt(b, []queue.Result{{ID: "z", Status: "accepted"}}); err == nil {
		t.Fatal("stale receipt applied")
	}
}

func TestBatchLimits(t *testing.T) {
	q, _ := open(t)
	var evs []queue.Event
	for i := 0; i < 2500; i++ {
		evs = append(evs, interval(fmt.Sprintf("e%04d", i), 1, int64(i), int64(i+1)))
	}
	must(t, q.Put(evs...))
	b, err := q.Freeze("inst", 2000, 10<<20)
	must(t, err)
	if len(b.Items) != 2000 {
		t.Fatalf("batch of %d events, want 2000", len(b.Items))
	}
	var arr []json.RawMessage
	must(t, json.Unmarshal(b.Events, &arr))
	if len(arr) != 2000 {
		t.Fatalf("events array of %d", len(arr))
	}
	must(t, q.DropFrozen(b))
	small, err := q.Freeze("inst", 2000, 4096)
	must(t, err)
	if len(small.Events) > 4096 || len(small.Items) == 0 {
		t.Fatalf("byte budget ignored: %d bytes, %d items", len(small.Events), len(small.Items))
	}
	if small.Seq != 2 {
		t.Fatalf("a dropped batch's sequence number was reused: %d", small.Seq)
	}
}

func TestNewInstallationDropsOldFrozenBatch(t *testing.T) {
	q, _ := open(t)
	must(t, q.Put(interval("a", 1, 100, 200)))
	b, _ := q.Freeze("old", 2000, 1<<20)
	nb, err := q.Freeze("new", 2000, 1<<20)
	must(t, err)
	if nb.InstallationID != "new" || nb.Seq != 1 || len(nb.Items) != 1 || b.Seq != 1 {
		t.Fatalf("new installation batch %+v", nb)
	}
}

func TestReplayRequeuesAcknowledged(t *testing.T) {
	q, now := open(t)
	day := int64(24 * time.Hour / time.Millisecond)
	base := t0.UnixMilli()
	must(t, q.Put(interval("old", 1, base-3*day, base-3*day+1000), interval("new", 1, base-day, base-day+1000)))
	b, _ := q.Freeze("inst", 2000, 1<<20)
	_, err := q.ApplyReceipt(b, []queue.Result{{ID: "old", Status: "accepted"}, {ID: "new", Status: "accepted"}})
	must(t, err)
	if nb, _ := q.Freeze("inst", 2000, 1<<20); nb != nil {
		t.Fatal("nothing should be pending")
	}
	n, err := q.Replay(t0.Add(-2 * 24 * time.Hour))
	must(t, err)
	if n != 1 {
		t.Fatalf("replayed %d, want 1", n)
	}
	rb, _ := q.Freeze("inst", 2000, 1<<20)
	if rb == nil || len(rb.Items) != 1 || rb.Items[0].ID != "new" || rb.Seq != 2 {
		t.Fatalf("replay batch %+v", rb)
	}
	// The server already has it: duplicate is fine and acknowledges it.
	sum, err := q.ApplyReceipt(rb, []queue.Result{{ID: "new", Status: "duplicate"}})
	must(t, err)
	if sum.Duplicate != 1 {
		t.Fatalf("summary %+v", sum)
	}
	if r, _, _ := q.AckedRev("new"); r != 1 {
		t.Fatal("duplicate did not acknowledge")
	}
	_ = now
}

func TestPurgeKeepsAcknowledgedFor14Days(t *testing.T) {
	q, now := open(t)
	must(t, q.Put(interval("a", 1, 1, 2), interval("p", 1, 3, 4)))
	b, _ := q.Freeze("inst", 2000, 1<<20)
	_, err := q.ApplyReceipt(b, []queue.Result{{ID: "a", Status: "accepted"}})
	must(t, err)
	*now = t0.Add(13 * 24 * time.Hour)
	must(t, q.Purge())
	if _, ok, _ := q.Get("a"); !ok {
		t.Fatal("acknowledged event purged before 14 days")
	}
	*now = t0.Add(15 * 24 * time.Hour)
	must(t, q.Purge())
	if _, ok, _ := q.Get("a"); ok {
		t.Fatal("acknowledged event kept after 14 days")
	}
	if _, ok, _ := q.Get("p"); !ok {
		t.Fatal("a pending event must never be purged")
	}
}

func TestStatsAndPendingSince(t *testing.T) {
	q, now := open(t)
	must(t, q.Put(interval("a", 1, 1, 2)))
	*now = t0.Add(time.Minute)
	must(t, q.Put(interval("a", 2, 1, 3))) // still pending: keeps its first pending time
	st, err := q.Stats()
	must(t, err)
	if st.Pending != 1 || st.OldestPendingSince != t0.UnixMilli() {
		t.Fatalf("stats %+v", st)
	}
	b, _ := q.Freeze("inst", 2000, 1<<20)
	_, err = q.ApplyReceipt(b, []queue.Result{{ID: "a", Status: "accepted"}})
	must(t, err)
	*now = t0.Add(2 * time.Minute)
	must(t, q.Put(interval("a", 3, 1, 4))) // new revision after the ack
	st, _ = q.Stats()
	if st.Pending != 1 || st.OldestPendingSince != t0.Add(2*time.Minute).UnixMilli() {
		t.Fatalf("stats after ack %+v", st)
	}
}

func TestConcurrentProcessesShareTheQueue(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "queue.db")
	q1, err := queue.Open(p)
	must(t, err)
	defer q1.Close()
	q2, err := queue.Open(p)
	must(t, err)
	defer q2.Close()
	done := make(chan error, 2)
	for i, q := range []*queue.Queue{q1, q2} {
		go func(i int, q *queue.Queue) {
			for j := 0; j < 50; j++ {
				if err := q.Put(interval(fmt.Sprintf("%d-%d", i, j), 1, 1, 2)); err != nil {
					done <- err
					return
				}
			}
			done <- nil
		}(i, q)
	}
	must(t, <-done)
	must(t, <-done)
	all, _ := q1.All()
	if len(all) != 100 {
		t.Fatalf("got %d events", len(all))
	}
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}
