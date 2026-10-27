package activity_test

import (
	"bytes"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/activity"
	"github.com/AndDev741/beConscient/desktop/internal/activity/activitytest"
	"github.com/AndDev741/beConscient/desktop/internal/events"
)

var t0 = time.Date(2026, 10, 10, 9, 0, 0, 0, time.UTC)

type harness struct {
	t     *testing.T
	clock *activitytest.Clock
	src   *activitytest.Source
	mon   *activity.Monitor
	out   []activity.Interval
	logs  *bytes.Buffer
	ids   int
}

func newHarness(t *testing.T, setup func(*activitytest.Source)) *harness {
	h := &harness{t: t, clock: activitytest.NewClock(t0), logs: &bytes.Buffer{}}
	h.src = activitytest.NewSource(h.clock)
	if setup != nil {
		setup(h.src)
	}
	log := slog.New(slog.NewTextHandler(h.logs, nil))
	h.mon = activity.NewMonitor(h.src, h.clock, activity.DefaultConfig(), func(time.Time) string {
		h.ids++
		return fmt.Sprintf("iv-%d", h.ids)
	}, func(ivs []activity.Interval) { h.out = append(h.out, ivs...) }, log)
	h.mon.Start()
	return h
}

// run polls every 5 s for d; with input the user types before each poll.
func (h *harness) run(d time.Duration, input bool) {
	for end := h.clock.Now().Add(d); h.clock.Now().Before(end); {
		h.clock.Advance(5 * time.Second)
		if input {
			h.src.Input()
		}
		h.mon.Poll()
	}
}

func (h *harness) last() map[string]activity.Interval {
	m := map[string]activity.Interval{}
	for _, iv := range h.out {
		m[iv.ID] = iv
	}
	return m
}

func TestMonitorWallClockJumpClosesInterval(t *testing.T) {
	for _, jump := range []time.Duration{time.Hour, -time.Hour, 20 * time.Second} {
		t.Run(jump.String(), func(t *testing.T) {
			h := newHarness(t, nil)
			h.run(10*time.Minute, true)
			preJump := h.clock.Now()
			h.mon.Checkpoint()
			h.clock.JumpWall(jump)
			h.run(time.Minute, true)
			h.mon.Checkpoint()
			last := h.last()
			if len(last) != 2 {
				t.Fatalf("got %d intervals, want the open one closed and a new one: %+v", len(last), h.out)
			}
			if iv := last["iv-1"]; !iv.End.Equal(preJump) {
				t.Fatalf("first interval ends %v, want %v (before the jump)", iv.End, preJump)
			}
			if iv := last["iv-2"]; !iv.Start.Equal(preJump.Add(jump + 5*time.Second)) {
				t.Fatalf("second interval starts %v, want the first poll after the jump", iv.Start)
			}
			if !strings.Contains(h.logs.String(), "wall clock jumped") {
				t.Fatal("the jump was not logged")
			}
		})
	}
}

func TestMonitorSmallDriftDoesNotSplit(t *testing.T) {
	h := newHarness(t, nil)
	h.run(5*time.Minute, true)
	h.clock.JumpWall(10 * time.Second)
	h.run(5*time.Minute, true)
	h.mon.Checkpoint()
	if n := len(h.last()); n != 1 {
		t.Fatalf("a 10 s adjustment split the interval into %d", n)
	}
}

func TestMonitorSuspendIsNotAClockJump(t *testing.T) {
	h := newHarness(t, nil)
	h.run(5*time.Minute, true)
	h.mon.Handle(activity.Signal{Kind: activity.SigPrepareForSleep, Value: true})
	h.clock.JumpWall(2 * time.Hour) // monotonic time stops during suspend
	h.mon.Handle(activity.Signal{Kind: activity.SigPrepareForSleep, Value: false})
	h.run(time.Minute, false)
	if strings.Contains(h.logs.String(), "wall clock jumped") {
		t.Fatal("a suspend was treated as a clock jump")
	}
	if h.mon.State() != activity.Locked {
		t.Fatalf("state %s after resume without input, want locked", h.mon.State())
	}
	h.run(10*time.Second, true)
	if h.mon.State() != activity.Active {
		t.Fatalf("state %s after input, want active", h.mon.State())
	}
}

func TestMonitorScreenSaverSignals(t *testing.T) {
	h := newHarness(t, nil)
	h.run(time.Minute, true)
	h.mon.Handle(activity.Signal{Kind: activity.SigScreenSaver, Value: true})
	if h.mon.State() != activity.Locked {
		t.Fatalf("state %s, want locked", h.mon.State())
	}
	lockAt := h.clock.Now()
	if iv := h.last()["iv-1"]; !iv.End.Equal(lockAt) {
		t.Fatalf("interval %+v should end at the lock", iv)
	}
	// LockedHint is ignored while the screensaver works.
	h.mon.Handle(activity.Signal{Kind: activity.SigLockedHint, Value: false})
	if h.mon.State() != activity.Locked {
		t.Fatal("LockedHint must not override the screensaver")
	}
	h.mon.Handle(activity.Signal{Kind: activity.SigScreenSaver, Value: false})
	if h.mon.State() != activity.Active {
		t.Fatalf("state %s after unlock, want active", h.mon.State())
	}
}

func TestMonitorFallsBackToLockedHint(t *testing.T) {
	h := newHarness(t, func(s *activitytest.Source) {
		s.ScreenSaverErr = errors.New("org.freedesktop.DBus.Error.ServiceUnknown")
		s.Locked = true
	})
	if h.mon.State() != activity.Locked {
		t.Fatalf("state %s, want locked from LockedHint", h.mon.State())
	}
	if !strings.Contains(h.logs.String(), "falling back to logind LockedHint") || !strings.Contains(h.logs.String(), "level=ERROR") {
		t.Fatalf("the fallback must be logged loudly:\n%s", h.logs.String())
	}
	if s := h.mon.Sensor(); s.State != events.StateOK || !strings.Contains(s.Detail, "logind") {
		t.Fatalf("sensor %+v should report the fallback", s)
	}
	// Unlock is seen by polling LockedHint.
	h.src.Set(func(s *activitytest.Source) { s.Locked = false })
	h.run(10*time.Second, true)
	if h.mon.State() != activity.Active {
		t.Fatalf("state %s after LockedHint=false, want active", h.mon.State())
	}
	// And by its PropertiesChanged signal.
	h.mon.Handle(activity.Signal{Kind: activity.SigLockedHint, Value: true})
	if h.mon.State() != activity.Locked {
		t.Fatalf("state %s after the LockedHint signal, want locked", h.mon.State())
	}
}

func TestMonitorInhibitorSignalReadsOnlyTheFlag(t *testing.T) {
	h := newHarness(t, func(s *activitytest.Source) { s.Media = true })
	h.run(time.Minute, true)
	calls := h.src.InhibitedCalls
	h.src.Set(func(s *activitytest.Source) { s.Inhibited = true })
	h.mon.Handle(activity.Signal{Kind: activity.SigInhibitors})
	if h.src.InhibitedCalls != calls+1 {
		t.Fatal("an inhibitor signal must re-read IsInhibited")
	}
	h.run(10*time.Minute, false)
	if h.mon.State() != activity.Passive {
		t.Fatalf("state %s, want passive with an inhibitor", h.mon.State())
	}
	_, inhibited, media, _ := h.mon.Flags()
	if !inhibited || !media {
		t.Fatalf("flags inhibited=%t media=%t, want both set", inhibited, media)
	}
	open, _ := h.mon.Open()
	if open.Reason != activity.ReasonInhibitor {
		t.Fatalf("open reason %s", open.Reason)
	}
	h.src.Set(func(s *activitytest.Source) { s.Inhibited = false })
	h.mon.Handle(activity.Signal{Kind: activity.SigInhibitors})
	if h.mon.State() != activity.Idle {
		t.Fatalf("state %s after the inhibitor ends, want idle", h.mon.State())
	}
}

func TestMonitorInhibitorWithoutMediaGoesIdle(t *testing.T) {
	// A keep-awake tool or a job left running overnight holds an idle
	// inhibitor with nothing playing and nobody at the keyboard.
	h := newHarness(t, func(s *activitytest.Source) { s.Inhibited = true })
	h.run(time.Minute, true)
	h.run(2*time.Hour, false)
	if h.mon.State() != activity.Idle {
		t.Fatalf("state %s, want idle with an inhibitor and no media playing", h.mon.State())
	}
	var counted time.Duration
	for _, iv := range h.last() {
		if iv.Reason != activity.ReasonInput {
			t.Fatalf("interval %+v counted without input", iv)
		}
		counted += iv.Duration()
	}
	if counted != time.Minute {
		t.Fatalf("counted %v, want only the minute of input", counted)
	}
}

func TestMonitorMediaPauseEndsPassive(t *testing.T) {
	h := newHarness(t, func(s *activitytest.Source) { s.Inhibited, s.Media = true, true })
	h.run(time.Minute, true)
	h.run(30*time.Minute, false)
	if h.mon.State() != activity.Passive {
		t.Fatalf("state %s, want passive while a player plays", h.mon.State())
	}
	h.src.Set(func(s *activitytest.Source) { s.Media = false })
	pausedAt := h.clock.Now().Add(5 * time.Second)
	h.run(5*time.Second, false)
	if h.mon.State() != activity.Idle {
		t.Fatalf("state %s after the player paused, want idle", h.mon.State())
	}
	for _, iv := range h.last() {
		if iv.Reason == activity.ReasonInhibitor && !iv.End.Equal(pausedAt) {
			t.Fatalf("inhibitor interval %+v should end at the poll that saw the pause, %v", iv, pausedAt)
		}
	}
}

func TestMonitorMediaFailureIsLoudAndConservative(t *testing.T) {
	h := newHarness(t, func(s *activitytest.Source) { s.Inhibited, s.Media = true, true })
	h.run(time.Minute, true)
	h.src.Set(func(s *activitytest.Source) { s.MediaErr = errors.New("org.freedesktop.DBus.Error.NoReply") })
	h.run(10*time.Minute, false)
	if h.mon.State() != activity.Idle {
		t.Fatalf("state %s, want idle when the players cannot be read", h.mon.State())
	}
	if c := strings.Count(h.logs.String(), "listing MPRIS players failed"); c != 1 {
		t.Fatalf("failure logged %d times, want once", c)
	}
	if s := h.mon.Sensor(); !strings.Contains(s.Detail, "media players unavailable") {
		t.Fatalf("sensor %+v should name the media players", s)
	}
	h.src.Set(func(s *activitytest.Source) { s.MediaErr = nil })
	h.run(5*time.Second, true)
	if h.mon.Sensor().Detail != "" {
		t.Fatalf("sensor detail %q after recovery", h.mon.Sensor().Detail)
	}
}

func TestMonitorPlayingPerConnection(t *testing.T) {
	h := newHarness(t, nil)
	h.run(time.Minute, true)
	h.mon.SetPlaying(1, true)
	h.mon.SetPlaying(2, true)
	h.run(10*time.Minute, false)
	if h.mon.State() != activity.Passive {
		t.Fatalf("state %s, want passive while playing", h.mon.State())
	}
	h.mon.DropConn(1)
	if h.mon.State() != activity.Passive {
		t.Fatal("another connection still plays")
	}
	h.mon.DropConn(2) // the browser closed: playing stops with it
	if h.mon.State() != activity.Idle {
		t.Fatalf("state %s, want idle when no connection plays", h.mon.State())
	}
}

func TestMonitorIdleMonitorFailureIsLoudAndConservative(t *testing.T) {
	h := newHarness(t, nil)
	h.run(time.Minute, true)
	h.src.Set(func(s *activitytest.Source) { s.IdleErr = errors.New("org.freedesktop.DBus.Error.NoReply") })
	h.run(10*time.Minute, true) // input happens, but cannot be seen
	if h.mon.State() != activity.Idle {
		t.Fatalf("state %s, want idle when the idle monitor fails", h.mon.State())
	}
	if c := strings.Count(h.logs.String(), "GetIdletime failed"); c != 1 {
		t.Fatalf("failure logged %d times, want once (not once per poll)", c)
	}
	if s := h.mon.Sensor(); s.State != events.StateStopped {
		t.Fatalf("sensor %+v, want stopped", s)
	}
	h.src.Set(func(s *activitytest.Source) { s.IdleErr = nil })
	h.run(10*time.Second, true)
	if h.mon.State() != activity.Active || h.mon.Sensor().State != events.StateOK {
		t.Fatal("did not recover when the idle monitor came back")
	}
}
