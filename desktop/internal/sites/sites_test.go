package sites_test

import (
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"testing"

	"github.com/AndDev741/beConscient/desktop/internal/events"
	"github.com/AndDev741/beConscient/desktop/internal/queue"
	"github.com/AndDev741/beConscient/desktop/internal/sites"
)

const (
	siteID = "0192f3a1-0000-7000-8000-00000000000b"
	min    = int64(60_000)
	base   = int64(1760090000000)
)

func openQ(t *testing.T) *queue.Queue {
	t.Helper()
	q, err := queue.Open(filepath.Join(t.TempDir(), "queue.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { q.Close() })
	return q
}

func msg(id string, rev int, start, end int64, focused, playing bool, extra string) []byte {
	return []byte(fmt.Sprintf(`{"t":"site","id":%q,"rev":%d,"site":"youtube","browser":"chrome","start":%d,"end":%d,
	  "playing":%t,"focused":%t,"title":"A video title","page_kind":null,"tz":"Europe/Lisbon","tz_offset_min":60%s}`,
		id, rev, start, end, playing, focused, extra))
}

func store(t *testing.T, q *queue.Queue, m []byte, open *queue.Span) {
	t.Helper()
	r, err := sites.Parse(m)
	if err != nil {
		t.Fatal(err)
	}
	if err := sites.Store(q, r, open); err != nil {
		t.Fatal(err)
	}
}

func siteEvents(t *testing.T, q *queue.Queue) []map[string]interface{} {
	t.Helper()
	all, err := q.All()
	if err != nil {
		t.Fatal(err)
	}
	var out []map[string]interface{}
	for _, ev := range all {
		if ev.Type != events.TypeSite {
			continue
		}
		var m map[string]interface{}
		json.Unmarshal(ev.Payload, &m)
		if bad, _ := events.Disallowed(ev.Payload); len(bad) > 0 {
			t.Fatalf("forbidden fields %v in %s", bad, ev.Payload)
		}
		out = append(out, m)
	}
	return out
}

func passive(t *testing.T, q *queue.Queue, id string, start, end int64) {
	t.Helper()
	p := fmt.Sprintf(`{"id":%q,"type":"desktop.activity","start":%d,"end":%d,"rev":1,"reason":"playing","tz_offset_min":60}`, id, start, end)
	if err := q.Put(queue.Event{ID: id, Rev: 1, Type: events.TypeActivity, Tag: "playing", T0: start, T1: end, Payload: []byte(p)}); err != nil {
		t.Fatal(err)
	}
}

func TestFocusedIntervalKeptAsIsWithoutFocused(t *testing.T) {
	q := openQ(t)
	store(t, q, msg(siteID, 3, base, base+20*min, true, false, `,"url":"https://www.youtube.com/watch?v=x"`), nil)
	evs := siteEvents(t, q)
	if len(evs) != 1 {
		t.Fatalf("got %d site events", len(evs))
	}
	e := evs[0]
	if e["id"] != siteID || e["rev"].(float64) != 3 || e["start"].(float64) != float64(base) || e["end"].(float64) != float64(base+20*min) {
		t.Fatalf("event %v changed", e)
	}
	for _, k := range []string{"focused", "url", "t", "page_kind"} {
		if _, ok := e[k]; ok {
			t.Fatalf("field %q must not be uploaded: %v", k, e)
		}
	}
	if e["title"] != "A video title" || e["playing"] != false {
		t.Fatalf("event %v", e)
	}
}

func TestUnfocusedPlayingKeptOnlyForPassiveParts(t *testing.T) {
	q := openQ(t)
	// The agent was Passive 10..20 and 30..40 minutes in; typing elsewhere.
	passive(t, q, "span-1", base+10*min, base+20*min)
	passive(t, q, "span-2", base+30*min, base+40*min)
	store(t, q, msg(siteID, 1, base, base+50*min, false, true, ""), nil)
	evs := siteEvents(t, q)
	if len(evs) != 2 {
		t.Fatalf("got %d pieces, want 2: %v", len(evs), evs)
	}
	var total float64
	for _, e := range evs {
		if e["id"] == siteID {
			t.Fatal("a piece must have its own id")
		}
		total += e["end"].(float64) - e["start"].(float64)
		if e["playing"] != true {
			t.Fatal("pieces are playing")
		}
	}
	if total != float64(20*min) {
		t.Fatalf("kept %v ms, want 20 min", total)
	}
}

func TestUnfocusedNotPlayingIsDropped(t *testing.T) {
	q := openQ(t)
	passive(t, q, "span-1", base, base+60*min)
	store(t, q, msg(siteID, 1, base, base+5*min, false, false, ""), nil)
	if evs := siteEvents(t, q); len(evs) != 0 {
		t.Fatalf("unfocused, not playing must never count: %v", evs)
	}
}

func TestUnfocusedWithoutPassiveIsDropped(t *testing.T) {
	q := openQ(t)
	store(t, q, msg(siteID, 1, base, base+5*min, false, true, ""), nil)
	if evs := siteEvents(t, q); len(evs) != 0 {
		t.Fatalf("music behind an editor while typing must stay computer time: %v", evs)
	}
}

func TestPieceIDsStableAcrossRevisions(t *testing.T) {
	q := openQ(t)
	open := &queue.Span{ID: "span-1", Start: base + 5*min, End: base + 8*min}
	store(t, q, msg(siteID, 1, base, base+8*min, false, true, ""), open)
	first := siteEvents(t, q)
	open.End = base + 12*min
	store(t, q, msg(siteID, 2, base, base+12*min, false, true, ""), open)
	second := siteEvents(t, q)
	if len(first) != 1 || len(second) != 1 || first[0]["id"] != second[0]["id"] {
		t.Fatalf("piece id changed: %v / %v", first, second)
	}
	if second[0]["rev"].(float64) != 2 || second[0]["start"] != first[0]["start"] || second[0]["end"].(float64) != float64(base+12*min) {
		t.Fatalf("second revision %v", second[0])
	}
	if want := sites.PieceID(siteID, "span-1", base+5*min); second[0]["id"] != want {
		t.Fatalf("piece id %v, want deterministic %s", second[0]["id"], want)
	}
	// An older revision of the site interval changes nothing.
	store(t, q, msg(siteID, 1, base, base+8*min, false, true, ""), open)
	if again := siteEvents(t, q); again[0]["rev"].(float64) != 2 {
		t.Fatal("an older revision rewrote the piece")
	}
}

func TestPassiveSpanAppearingLaterIsReconciled(t *testing.T) {
	q := openQ(t)
	// The video ended before the agent noticed it had been passive since 09:02.
	store(t, q, msg(siteID, 1, base, base+20*min, false, true, ""), nil)
	if evs := siteEvents(t, q); len(evs) != 0 {
		t.Fatal("no passive span yet")
	}
	passive(t, q, "span-late", base+2*min, base+30*min)
	if err := sites.Reconcile(q, queue.Span{ID: "span-late", Start: base + 2*min, End: base + 30*min}); err != nil {
		t.Fatal(err)
	}
	evs := siteEvents(t, q)
	if len(evs) != 1 || evs[0]["start"].(float64) != float64(base+2*min) || evs[0]["end"].(float64) != float64(base+20*min) {
		t.Fatalf("reconciled pieces %v", evs)
	}
}

func TestLinkedInNeverCarriesTitleAndPageKindIsClosed(t *testing.T) {
	q := openQ(t)
	m := `{"t":"site","id":"0192f3a1-0000-7000-8000-00000000000c","rev":1,"site":"linkedin","browser":"brave",
	  "start":1760091300000,"end":1760091600000,"playing":false,"focused":true,"title":"Jane Doe | LinkedIn",
	  "page_kind":"company-acme","url":"https://www.linkedin.com/in/jane"}`
	store(t, q, []byte(m), nil)
	e := siteEvents(t, q)[0]
	if _, ok := e["title"]; ok {
		t.Fatal("LinkedIn title uploaded")
	}
	if e["page_kind"] != "other" || e["browser"] != "brave" || e["site"] != "linkedin" {
		t.Fatalf("event %v", e)
	}
}

func TestInvalidSitesAreDroppedButAcknowledgeable(t *testing.T) {
	for name, m := range map[string]string{
		"other site":       `{"t":"site","id":"0192f3a1-0000-7000-8000-000000000104","rev":1,"site":"example.com","browser":"chrome","start":1,"end":2,"focused":true}`,
		"end before start": `{"t":"site","id":"0192f3a1-0000-7000-8000-000000000105","rev":1,"site":"youtube","browser":"chrome","start":5,"end":2,"focused":true}`,
		"bad id":           `{"t":"site","id":"x","rev":1,"site":"youtube","browser":"chrome","start":1,"end":2,"focused":true}`,
		"no rev":           `{"t":"site","id":"0192f3a1-0000-7000-8000-000000000106","site":"youtube","browser":"chrome","start":1,"end":2,"focused":true}`,
	} {
		t.Run(name, func(t *testing.T) {
			_, err := sites.Parse([]byte(m))
			if !errors.Is(err, sites.ErrDropped) {
				t.Fatalf("got %v, want dropped", err)
			}
		})
	}
	r, err := sites.Parse([]byte(`{"t":"site","id":"0192f3a1-0000-7000-8000-000000000107","rev":1,"site":"youtube","browser":"firefox","start":1,"end":2,"focused":true}`))
	if err != nil || r.Browser != "other" {
		t.Fatalf("unknown browser should become other: %+v %v", r, err)
	}
}

func TestLongTitleTruncated(t *testing.T) {
	long := ""
	for i := 0; i < 400; i++ {
		long += "é"
	}
	r, err := sites.Parse([]byte(`{"t":"site","id":"0192f3a1-0000-7000-8000-000000000108","rev":1,"site":"youtube","browser":"chrome","start":1,"end":2,"focused":true,"title":"` + long + `"}`))
	if err != nil {
		t.Fatal(err)
	}
	if n := len([]rune(r.Title)); n != 300 {
		t.Fatalf("title has %d characters, want 300", n)
	}
}

func TestShortenedSiteRewritesPieceToZero(t *testing.T) {
	q := openQ(t)
	passive(t, q, "span-1", base+10*min, base+20*min)
	store(t, q, msg(siteID, 1, base, base+15*min, false, true, ""), nil)
	store(t, q, msg(siteID, 2, base, base+5*min, false, true, ""), nil)
	evs := siteEvents(t, q)
	if len(evs) != 1 || evs[0]["start"] != evs[0]["end"] || evs[0]["rev"].(float64) != 2 {
		t.Fatalf("piece %v should be rewritten with zero length", evs)
	}
}
