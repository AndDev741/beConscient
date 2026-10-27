package agent_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/activity"
	"github.com/AndDev741/beConscient/desktop/internal/activity/activitytest"
	"github.com/AndDev741/beConscient/desktop/internal/agent"
	"github.com/AndDev741/beConscient/desktop/internal/events"
	"github.com/AndDev741/beConscient/desktop/internal/nativehost"
	"github.com/AndDev741/beConscient/desktop/internal/queue"
	"github.com/AndDev741/beConscient/desktop/internal/tz"
)

var t0 = time.Date(2026, 10, 10, 9, 0, 0, 0, time.UTC)

type harness struct {
	t     *testing.T
	clock *activitytest.Clock
	src   *activitytest.Source
	q     *queue.Queue
	a     *agent.Agent
	logs  *bytes.Buffer
}

func newHarness(t *testing.T, sock string) *harness {
	t.Helper()
	q, err := queue.Open(filepath.Join(t.TempDir(), "queue.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { q.Close() })
	clock := activitytest.NewClock(t0)
	q.SetNow(clock.Now)
	src := activitytest.NewSource(clock)
	logs := &bytes.Buffer{}
	a := agent.New(agent.Options{
		Queue: q, Source: src, Delayer: src, Clock: clock, TZ: tz.Fixed("Europe/Lisbon", 60),
		Log: slog.New(slog.NewTextHandler(logs, nil)), SocketPath: sock,
	})
	return &harness{t: t, clock: clock, src: src, q: q, a: a, logs: logs}
}

// run advances d with a poll every 5 s and a checkpoint every 5 minutes.
func (h *harness) run(d time.Duration, input bool) {
	for end := h.clock.Now().Add(d); h.clock.Now().Before(end); {
		h.clock.Advance(5 * time.Second)
		if input {
			h.src.Input()
		}
		h.a.Poll()
		if h.clock.Now().Sub(t0)%(5*time.Minute) == 0 {
			h.a.Checkpoint()
		}
	}
}

func (h *harness) send(conn uint64, msg string) nativehost.Ack {
	h.t.Helper()
	reply, err := h.a.HandleSync(conn, []byte(msg))
	if err != nil {
		h.t.Fatal(err)
	}
	var ack nativehost.Ack
	if err := json.Unmarshal(reply, &ack); err != nil || ack.T != "ack" {
		h.t.Fatalf("reply %s", reply)
	}
	return ack
}

func (h *harness) byType(typ string) []map[string]interface{} {
	h.t.Helper()
	all, err := h.q.All()
	if err != nil {
		h.t.Fatal(err)
	}
	var out []map[string]interface{}
	for _, ev := range all {
		if ev.Type == typ {
			var m map[string]interface{}
			json.Unmarshal(ev.Payload, &m)
			out = append(out, m)
		}
	}
	return out
}

func ms(t time.Time) float64 { return float64(t.UnixMilli()) }

func siteMsg(id string, start, end time.Time, focused, playing bool) string {
	return fmt.Sprintf(`{"t":"site","id":%q,"rev":1,"site":"youtube","browser":"chrome","start":%d,"end":%d,
	  "playing":%t,"focused":%t,"title":"A video title","page_kind":null,"tz":"Europe/Lisbon","tz_offset_min":60,
	  "url":"https://www.youtube.com/watch?v=secret","window_title":"Client X - Jira"}`,
		id, start.UnixMilli(), end.UnixMilli(), playing, focused)
}

func TestActivityIsWrittenOnStateChangeAndEveryFiveMinutes(t *testing.T) {
	h := newHarness(t, "")
	h.a.Start()
	h.run(12*time.Minute, true)
	acts := h.byType(events.TypeActivity)
	if len(acts) != 1 {
		t.Fatalf("activity rows %v", acts)
	}
	ev, _, _ := h.q.Get(acts[0]["id"].(string))
	if ev.Rev < 3 || ev.T1 != t0.Add(10*time.Minute).UnixMilli() {
		t.Fatalf("stored revision %d ending %d, want the 09:10 checkpoint", ev.Rev, ev.T1)
	}
	if acts[0]["tz"] != "Europe/Lisbon" || acts[0]["tz_offset_min"].(float64) != 60 || acts[0]["reason"] != "input" {
		t.Fatalf("activity %v", acts[0])
	}
	if hs := h.byType(events.TypeHealth); len(hs) != 3 { // start, 09:05, 09:10
		t.Fatalf("health records %d, want 3", len(hs))
	}
}

func TestSiteMessagesAndHeartbeats(t *testing.T) {
	h := newHarness(t, "")
	h.a.Start()
	h.run(time.Minute, true)
	focused := "0192f3a1-0000-7000-8000-0000000000f1"
	ack := h.send(1, siteMsg(focused, t0, t0.Add(time.Minute), true, false))
	if len(ack.IDs) != 1 || ack.IDs[0] != focused {
		t.Fatalf("ack %+v", ack)
	}
	other := h.send(1, `{"t":"site","id":"0192f3a1-0000-7000-8000-0000000000f2","rev":1,"site":"example.com","browser":"chrome","start":1,"end":2,"focused":true}`)
	if len(other.IDs) != 1 {
		t.Fatal("a dropped site interval must still be acknowledged")
	}
	if a := h.send(1, `{"t":"heartbeat","browser":"chrome","version":"0.1.0","at":`+fmt.Sprint(t0.UnixMilli())+`}`); len(a.IDs) != 0 {
		t.Fatalf("heartbeat ack %+v", a)
	}
	h.run(4*time.Minute, true) // 09:05 checkpoint writes a health record
	sites := h.byType(events.TypeSite)
	if len(sites) != 1 || sites[0]["id"] != focused {
		t.Fatalf("sites %v", sites)
	}
	hs := h.byType(events.TypeHealth)
	last := hs[len(hs)-1]["sensors"].(map[string]interface{})
	chrome := last["extension_chrome"].(map[string]interface{})
	brave := last["extension_brave"].(map[string]interface{})
	if chrome["state"] != "ok" || brave["state"] != "unknown" {
		t.Fatalf("sensors %v", last)
	}
	if last["activity"].(map[string]interface{})["state"] != "ok" {
		t.Fatalf("activity sensor %v", last["activity"])
	}
}

func TestUnfocusedPlayingCountsOnlyWhilePassive(t *testing.T) {
	h := newHarness(t, "")
	h.a.Start()
	h.send(7, `{"t":"playing","playing":true,"at":1}`)
	h.run(2*time.Minute, true) // typing with music in a background window
	lastInput := h.clock.Now()
	h.run(13*time.Minute, false)
	if st := h.a.Monitor().State(); st != activity.Passive {
		t.Fatalf("state %s, want passive", st)
	}
	id := "0192f3a1-0000-7000-8000-0000000000f3"
	h.send(7, siteMsg(id, t0, h.clock.Now(), false, true))
	sites := h.byType(events.TypeSite)
	if len(sites) != 1 {
		t.Fatalf("sites %v", sites)
	}
	s := sites[0]
	if s["id"] == id || s["start"].(float64) != ms(lastInput) || s["end"].(float64) != ms(h.clock.Now()) {
		t.Fatalf("piece %v, want only the passive part %v..%v", s, lastInput, h.clock.Now())
	}
	if _, ok := s["focused"]; ok {
		t.Fatal("focused uploaded")
	}
	// The passive interval keeps growing; the next checkpoint extends the piece.
	h.send(7, strings.Replace(siteMsg(id, t0, h.clock.Now().Add(5*time.Minute), false, true), `"rev":1`, `"rev":2`, 1))
	h.run(5*time.Minute, false)
	sites = h.byType(events.TypeSite)
	if len(sites) != 1 || sites[0]["end"].(float64) != ms(h.clock.Now()) {
		t.Fatalf("piece after checkpoint %v", sites)
	}
	// Closing the browser connection ends playing: idle.
	h.a.DropConnection(7)
	if st := h.a.Monitor().State(); st != activity.Idle {
		t.Fatalf("state %s after the browser closed, want idle", st)
	}
}

func TestSuspendWritesExpectedStopBeforeReleasingInhibitor(t *testing.T) {
	h := newHarness(t, "")
	h.a.Start()
	if h.src.DelayTaken != 1 {
		t.Fatal("delay inhibitor not taken at start")
	}
	h.run(3*time.Minute, true)
	sawStop := false
	h.src.OnRelease = func() {
		hs := h.byType(events.TypeHealth)
		sawStop = hs[len(hs)-1]["expected_stop"] == true
	}
	h.a.HandleSignal(activity.Signal{Kind: activity.SigPrepareForSleep, Value: true})
	if !sawStop {
		t.Fatal("the expected_stop health record must be written before the inhibitor is released")
	}
	acts := h.byType(events.TypeActivity)
	if acts[0]["end"].(float64) != ms(h.clock.Now()) {
		t.Fatalf("interval %v not closed at the suspend", acts[0])
	}
	h.clock.JumpWall(time.Hour)
	h.a.HandleSignal(activity.Signal{Kind: activity.SigPrepareForSleep, Value: false})
	if h.src.DelayTaken != 2 {
		t.Fatal("delay inhibitor not taken again after resume")
	}
	n := len(h.byType(events.TypeActivity))
	h.run(10*time.Minute, false)
	if len(h.byType(events.TypeActivity)) != n {
		t.Fatal("time counted after resume without input")
	}
	h.a.HandleSignal(activity.Signal{Kind: activity.SigPrepareForShutdown, Value: true})
	hs := h.byType(events.TypeHealth)
	if hs[len(hs)-1]["expected_stop"] != true {
		t.Fatal("no expected_stop before shutdown")
	}
}

func TestNoForbiddenFieldsEverProduced(t *testing.T) {
	h := newHarness(t, "")
	h.src.Set(func(s *activitytest.Source) { s.Inhibited, s.Media = true, true })
	h.a.Start()
	h.run(2*time.Minute, true)
	h.send(1, siteMsg("0192f3a1-0000-7000-8000-0000000000a1", t0, t0.Add(time.Minute), true, true))
	h.send(1, `{"t":"site","id":"0192f3a1-0000-7000-8000-0000000000a2","rev":1,"site":"linkedin","browser":"brave","start":1,"end":2,
	  "focused":true,"title":"Jane Doe | LinkedIn","page_kind":"profile","url":"https://www.linkedin.com/in/jane"}`)
	h.send(1, `{"t":"heartbeat","browser":"brave","version":"0.1.0","at":1,"app_id":"org.example.App"}`)
	h.run(20*time.Minute, false)
	h.send(1, siteMsg("0192f3a1-0000-7000-8000-0000000000a3", t0, h.clock.Now(), false, true))
	h.a.HandleSignal(activity.Signal{Kind: activity.SigScreenSaver, Value: true})
	h.a.Stop()

	all, _ := h.q.All()
	types := map[string]int{}
	for _, ev := range all {
		types[ev.Type]++
		bad, err := events.Disallowed(ev.Payload)
		if err != nil || len(bad) > 0 {
			t.Fatalf("event %s has disallowed fields %v", ev.Payload, bad)
		}
		for _, needle := range []string{"http", "Jane", "Jira", "window", "org.example", "/org/gnome", "focused"} {
			if strings.Contains(string(ev.Payload), needle) {
				t.Fatalf("event leaks %q: %s", needle, ev.Payload)
			}
		}
	}
	if types[events.TypeActivity] < 2 || types[events.TypeSite] < 3 || types[events.TypeHealth] < 2 {
		t.Fatalf("scenario too small: %v", types)
	}
}

func TestRunServesSocketAndStopsCleanly(t *testing.T) {
	dir, err := os.MkdirTemp("", "bca")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	sock := filepath.Join(dir, "beconscient", "agent.sock")
	h := newHarness(t, sock)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error)
	go func() { done <- h.a.Run(ctx) }()
	var c *nativehost.Client
	for i := 0; i < 200; i++ {
		if c, err = nativehost.Dial(sock); err == nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if c == nil {
		t.Fatalf("agent socket never came up: %v", err)
	}
	id := "0192f3a1-0000-7000-8000-0000000000c1"
	reply, err := c.Roundtrip([]byte(siteMsg(id, t0, t0.Add(time.Minute), true, false)))
	if err != nil {
		t.Fatal(err)
	}
	var ack nativehost.Ack
	json.Unmarshal(reply, &ack)
	if len(ack.IDs) != 1 || ack.IDs[0] != id {
		t.Fatalf("ack %s", reply)
	}
	if _, ok, _ := h.q.Get(id); !ok {
		t.Fatal("acknowledged before a durable write")
	}
	h.clock.Advance(time.Second)
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Run did not stop")
	}
	c.Close()
	hs := h.byType(events.TypeHealth)
	if hs[len(hs)-1]["expected_stop"] != true {
		t.Fatal("stopping the agent must record an expected stop")
	}
	if _, err := os.Lstat(sock); !os.IsNotExist(err) {
		t.Fatal("socket left behind")
	}
	var buf bytes.Buffer
	if err := agent.WriteStatus(&buf, agent.StatusInput{Queue: h.q, SocketPath: sock, Now: h.clock.Now()}); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	for _, want := range []string{"Agent:        not running", "Paired:       no", "Queue:", "extension_chrome", "activity"} {
		if !strings.Contains(out, want) {
			t.Fatalf("status lacks %q:\n%s", want, out)
		}
	}
}

func TestSecondAgentExitsWithoutMeasuring(t *testing.T) {
	dir, err := os.MkdirTemp("", "bca")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	sock := filepath.Join(dir, "agent.sock")
	first := newHarness(t, sock)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error)
	go func() { done <- first.a.Run(ctx) }()
	defer func() { cancel(); <-done }()
	for i := 0; i < 200; i++ {
		if c, err := nativehost.Dial(sock); err == nil {
			c.Close()
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	second := newHarness(t, sock)
	if err := second.a.Run(context.Background()); !errors.Is(err, nativehost.ErrAgentRunning) {
		t.Fatalf("second agent: %v", err)
	}
	if all, _ := second.q.All(); len(all) != 0 {
		t.Fatalf("the second agent wrote %d events", len(all))
	}
}
