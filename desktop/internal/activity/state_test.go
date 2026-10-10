package activity

import (
	"fmt"
	"testing"
	"time"
)

var t0 = time.Date(2026, 10, 10, 9, 0, 0, 0, time.UTC)

// sim drives a Machine with a fake clock: one poll every 5 s and a
// checkpoint every 5 minutes, like the agent loop.
type sim struct {
	t         *testing.T
	m         *Machine
	now       time.Time
	lastInput time.Time
	out       []Interval
	ids       int
}

func newSim(t *testing.T) *sim {
	s := &sim{t: t, now: t0, lastInput: t0}
	s.m = NewMachine(DefaultConfig(), func(time.Time) string {
		s.ids++
		return fmt.Sprintf("iv-%d", s.ids)
	})
	return s
}

func (s *sim) collect(ivs []Interval) { s.out = append(s.out, ivs...) }

func (s *sim) start(locked, inhibited, playing bool) {
	s.collect(s.m.Start(s.now, s.now.Sub(s.lastInput), locked, inhibited, playing))
}

// run advances d in 5 s polls. With input, the user types right before every
// poll.
func (s *sim) run(d time.Duration, input bool) {
	end := s.now.Add(d)
	for s.now.Before(end) {
		s.now = s.now.Add(5 * time.Second)
		if input {
			s.lastInput = s.now
		}
		s.collect(s.m.Poll(s.now, s.now.Sub(s.lastInput)))
		if s.now.Sub(t0)%(5*time.Minute) == 0 {
			s.collect(s.m.Checkpoint(s.now))
		}
	}
}

func (s *sim) at(d time.Duration) time.Time { return t0.Add(d) }

// final returns the latest revision of every interval, in order of first
// appearance, and checks the revision rules on the way.
func (s *sim) final() []Interval {
	s.t.Helper()
	last := map[string]Interval{}
	var order []string
	for _, iv := range s.out {
		prev, seen := last[iv.ID]
		if !seen {
			order = append(order, iv.ID)
			if iv.Rev != 1 {
				s.t.Fatalf("%s starts at rev %d", iv.ID, iv.Rev)
			}
		} else {
			if iv.Rev != prev.Rev+1 {
				s.t.Fatalf("%s rev %d follows rev %d", iv.ID, iv.Rev, prev.Rev)
			}
			if !iv.Start.Equal(prev.Start) {
				s.t.Fatalf("%s start changed from %v to %v", iv.ID, prev.Start, iv.Start)
			}
		}
		if iv.End.Before(iv.Start) {
			s.t.Fatalf("%s ends before it starts", iv.ID)
		}
		last[iv.ID] = iv
	}
	out := make([]Interval, 0, len(order))
	for _, id := range order {
		out = append(out, last[id])
	}
	// Intervals never overlap.
	for i := 1; i < len(out); i++ {
		if out[i].Start.Before(out[i-1].End) {
			s.t.Fatalf("interval %s %v overlaps %s ending %v", out[i].ID, out[i].Start, out[i-1].ID, out[i-1].End)
		}
	}
	return out
}

func (s *sim) counted() time.Duration {
	var d time.Duration
	for _, iv := range s.final() {
		d += iv.Duration()
	}
	return d
}

func (s *sim) wantState(st State) {
	s.t.Helper()
	if got := s.m.State(); got != st {
		s.t.Fatalf("state %s, want %s", got, st)
	}
}

func TestThirtyMinutesOfInputIsOneInterval(t *testing.T) {
	s := newSim(t)
	s.start(false, false, false)
	s.run(30*time.Minute, true)
	s.collect(s.m.SetLocked(s.now, true))

	f := s.final()
	if len(f) != 1 {
		t.Fatalf("got %d intervals, want 1: %+v", len(f), f)
	}
	if f[0].Reason != ReasonInput || !f[0].Start.Equal(t0) || f[0].Duration() != 30*time.Minute {
		t.Fatalf("interval %+v, want 30 min of input from %v", f[0], t0)
	}
	if len(s.out) < 7 {
		t.Fatalf("only %d revisions; the interval must be written every 5 minutes", len(s.out))
	}
}

func TestFiveMinutesIdleTrimsBackToLastInputAsNewRevision(t *testing.T) {
	s := newSim(t)
	s.start(false, false, false)
	s.run(8*time.Minute, true) // last input at 09:08
	s.run(12*time.Minute, false)
	s.wantState(Idle)

	f := s.final()
	if len(f) != 1 {
		t.Fatalf("got %d intervals: %+v", len(f), f)
	}
	if !f[0].End.Equal(s.at(8 * time.Minute)) {
		t.Fatalf("end %v, want the last input %v", f[0].End, s.at(8*time.Minute))
	}
	// The checkpoint at 09:10 wrote a later end; the trim is a newer revision.
	n := len(s.out)
	trim, before := s.out[n-1], s.out[n-2]
	if trim.ID != before.ID || trim.Rev != before.Rev+1 || !trim.End.Before(before.End) {
		t.Fatalf("trim %+v does not follow %+v as a shorter revision", trim, before)
	}
	if !s.m.Since().Equal(s.at(13 * time.Minute)) {
		t.Fatalf("idle since %v, want 09:13", s.m.Since())
	}
}

func TestAE9ThreeHourVideoCountsTwoHours(t *testing.T) {
	for _, tc := range []struct {
		name               string
		inhibited, playing bool
		reason             Reason
	}{
		{"extension playing", false, true, ReasonPlaying},
		{"inhibitor only", true, false, ReasonInhibitor},
		{"both", true, true, ReasonPlaying},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := newSim(t)
			s.start(false, tc.inhibited, tc.playing)
			s.run(time.Minute, true) // press play at 09:01
			s.run(3*time.Hour, false)
			s.wantState(Idle)

			f := s.final()
			if len(f) != 2 {
				t.Fatalf("got %d intervals: %+v", len(f), f)
			}
			in, passive := f[0], f[1]
			if in.Reason != ReasonInput || in.Duration() != time.Minute {
				t.Fatalf("input interval %+v", in)
			}
			if passive.Reason != tc.reason || !passive.Start.Equal(s.at(time.Minute)) || passive.Duration() != 2*time.Hour {
				t.Fatalf("passive interval %+v, want 2 h of %s from 09:01", passive, tc.reason)
			}
			if got := s.counted(); got != 2*time.Hour+time.Minute {
				t.Fatalf("counted %v, want 2h1m", got)
			}
		})
	}
}

func TestInhibitorKeepsCountingWithReasonInhibitor(t *testing.T) {
	s := newSim(t)
	s.start(false, true, false) // a call holds an idle inhibitor
	s.run(2*time.Minute, true)
	s.run(40*time.Minute, false)
	s.wantState(Passive)

	f := s.final()
	last := f[len(f)-1]
	if last.Reason != ReasonInhibitor || !last.Start.Equal(s.at(2*time.Minute)) {
		t.Fatalf("open interval %+v, want reason inhibitor from 09:02", last)
	}
	if !last.End.Equal(s.at(40 * time.Minute)) {
		t.Fatalf("open interval written up to %v, want the 09:40 checkpoint", last.End)
	}
	open, ok := s.m.Open(s.now)
	if !ok || !open.End.Equal(s.now) {
		t.Fatalf("open interval %+v should still be counting at %v", open, s.now)
	}
}

func TestLockClosesAtLockTime(t *testing.T) {
	s := newSim(t)
	s.start(false, false, false)
	s.run(12*time.Minute, true)
	lockAt := s.now.Add(3 * time.Second)
	s.collect(s.m.SetLocked(lockAt, true))
	s.wantState(Locked)
	s.now = lockAt
	s.run(5*time.Minute, true) // typing the password does not count
	if f := s.final(); len(f) != 1 || !f[0].End.Equal(lockAt) {
		t.Fatalf("intervals %+v, want one ending at the lock %v", f, lockAt)
	}

	unlockAt := s.now
	s.collect(s.m.SetLocked(unlockAt, false))
	s.wantState(Active)
	s.run(time.Minute, true)
	f := s.final()
	if len(f) != 2 || f[1].ID == f[0].ID || !f[1].Start.Equal(unlockAt) {
		t.Fatalf("after unlock: %+v, want a new interval from %v", f, unlockAt)
	}
}

func TestLockDuringPassiveAndIdle(t *testing.T) {
	s := newSim(t)
	s.start(false, true, false)
	s.run(time.Minute, true)
	s.run(20*time.Minute, false)
	s.wantState(Passive)
	s.collect(s.m.SetLocked(s.now, true))
	s.wantState(Locked)
	f := s.final()
	if last := f[len(f)-1]; last.Reason != ReasonInhibitor || !last.End.Equal(s.now) {
		t.Fatalf("passive interval %+v should end at the lock", last)
	}

	s2 := newSim(t)
	s2.start(false, false, false)
	s2.run(time.Minute, true)
	s2.run(10*time.Minute, false)
	s2.wantState(Idle)
	n := len(s2.out)
	s2.collect(s2.m.SetLocked(s2.now, true))
	s2.wantState(Locked)
	if len(s2.out) != n {
		t.Fatal("locking while idle must not write anything")
	}
}

func TestSuspendClosesAndNothingCountsUntilInput(t *testing.T) {
	s := newSim(t)
	s.start(false, false, false)
	s.run(10*time.Minute, true)
	s.collect(s.m.Suspend(s.now))
	s.wantState(Suspended)
	suspendAt := s.now
	if f := s.final(); len(f) != 1 || !f[0].End.Equal(suspendAt) {
		t.Fatalf("intervals %+v, want one closed at the suspend", f)
	}

	// Resume an hour later without a lock screen; no input for 10 minutes.
	s.now = s.now.Add(time.Hour)
	s.collect(s.m.Resume(s.now))
	s.wantState(Locked)
	n := len(s.out)
	s.run(10*time.Minute, false)
	if len(s.out) != n {
		t.Fatalf("something counted after resume without input: %+v", s.out[n:])
	}
	s.wantState(Locked)

	s.run(5*time.Second, true)
	s.wantState(Active)
	s.run(time.Minute, true)
	f := s.final()
	if len(f) != 2 || f[1].Start.Before(suspendAt.Add(time.Hour)) {
		t.Fatalf("after input: %+v, want a new interval starting after the resume", f)
	}
}

func TestSuspendWithLockScreenWaitsForUnlock(t *testing.T) {
	s := newSim(t)
	s.start(false, false, false)
	s.run(5*time.Minute, true)
	s.collect(s.m.SetLocked(s.now, true)) // GNOME locks before suspending
	s.collect(s.m.Suspend(s.now))
	s.now = s.now.Add(30 * time.Minute)
	s.collect(s.m.Resume(s.now))
	s.run(time.Minute, true) // typing the password
	s.wantState(Locked)
	s.collect(s.m.SetLocked(s.now, false))
	s.wantState(Active)
}

func TestMissedResumeSignal(t *testing.T) {
	s := newSim(t)
	s.start(false, false, false)
	s.run(5*time.Minute, true)
	s.collect(s.m.Suspend(s.now))
	s.run(30*time.Second, true) // polls during the delay inhibitor are ignored
	s.wantState(Suspended)
	s.now = s.now.Add(2 * time.Hour)
	s.run(5*time.Second, false)
	s.wantState(Locked)
	s.run(5*time.Second, true)
	s.wantState(Active)
}

func TestReasonChangeStartsNewInterval(t *testing.T) {
	s := newSim(t)
	s.start(false, true, false)
	s.run(time.Minute, true)
	s.run(10*time.Minute, false)
	s.wantState(Passive)
	switchAt := s.now
	s.collect(s.m.SetPlaying(s.now, true))
	s.run(10*time.Minute, false)

	f := s.final()
	if len(f) != 3 {
		t.Fatalf("got %d intervals: %+v", len(f), f)
	}
	if f[1].Reason != ReasonInhibitor || !f[1].End.Equal(switchAt) {
		t.Fatalf("inhibitor interval %+v should end at %v", f[1], switchAt)
	}
	if f[2].Reason != ReasonPlaying || !f[2].Start.Equal(switchAt) {
		t.Fatalf("playing interval %+v should start at %v", f[2], switchAt)
	}
}

func TestPassiveToActiveOnInput(t *testing.T) {
	s := newSim(t)
	s.start(false, true, false)
	s.run(time.Minute, true)
	s.run(20*time.Minute, false)
	s.wantState(Passive)
	s.run(5*time.Second, true)
	s.wantState(Active)
	inputAt := s.now
	s.run(time.Minute, true)
	f := s.final()
	if len(f) != 3 || f[1].Reason != ReasonInhibitor || !f[1].End.Equal(inputAt) ||
		f[2].Reason != ReasonInput || !f[2].Start.Equal(inputAt) {
		t.Fatalf("intervals %+v, want passive up to the input at %v and a new input interval", f, inputAt)
	}
}

func TestPassiveInputAfterCapEndsAtCap(t *testing.T) {
	s := newSim(t)
	s.start(false, true, false)
	s.run(time.Minute, true)
	s.run(10*time.Minute, false)
	// The agent stalls for 3 hours, then sees input.
	s.now = s.now.Add(3 * time.Hour)
	s.lastInput = s.now
	s.collect(s.m.Poll(s.now, 0))
	f := s.final()
	if f[1].Duration() != 2*time.Hour {
		t.Fatalf("passive interval %+v must be capped at 2 h", f[1])
	}
}

func TestVideoEndsGoesIdle(t *testing.T) {
	s := newSim(t)
	s.start(false, false, true)
	s.run(time.Minute, true)
	s.run(20*time.Minute, false)
	s.wantState(Passive)
	endAt := s.now
	s.collect(s.m.SetPlaying(s.now, false))
	s.wantState(Idle)
	f := s.final()
	if last := f[len(f)-1]; last.Reason != ReasonPlaying || !last.End.Equal(endAt) {
		t.Fatalf("playing interval %+v should end when the video ends", last)
	}
	// A video that starts while idle does not count until input.
	n := len(s.out)
	s.collect(s.m.SetPlaying(s.now, true))
	s.run(10*time.Minute, false)
	s.wantState(Idle)
	if len(s.out) != n {
		t.Fatal("idle must not become passive without input")
	}
}

func TestStartIdleOrLocked(t *testing.T) {
	s := newSim(t)
	s.lastInput = t0.Add(-time.Hour)
	s.start(false, false, false)
	s.run(time.Minute, false)
	s.wantState(Idle)
	if len(s.out) != 0 {
		t.Fatalf("idle start wrote %+v", s.out)
	}

	s2 := newSim(t)
	s2.start(true, false, false)
	s2.wantState(Locked)
	s2.run(time.Minute, true)
	s2.wantState(Locked)
	if len(s2.out) != 0 {
		t.Fatal("locked start must not count")
	}

	// Started with input 30 s ago: count from the start, not before it.
	s3 := newSim(t)
	s3.lastInput = t0.Add(-30 * time.Second)
	s3.start(false, false, false)
	s3.run(time.Minute, true)
	if f := s3.final(); len(f) != 1 || !f[0].Start.Equal(t0) {
		t.Fatalf("start interval %+v should begin at agent start", f)
	}
}

func TestClockJumpRestartsInterval(t *testing.T) {
	s := newSim(t)
	s.start(false, false, false)
	s.run(10*time.Minute, true)
	before := s.final()[0]
	// The wall clock jumps forward an hour.
	s.now = s.now.Add(time.Hour)
	s.lastInput = s.now
	s.collect(s.m.ClockJump(s.now, 0))
	s.run(time.Minute, true)
	f := s.final()
	if len(f) != 2 || f[0].ID != before.ID || f[1].ID == before.ID || !f[1].Start.Equal(s.at(70*time.Minute)) {
		t.Fatalf("intervals %+v, want the old one closed and a new one from the jump", f)
	}
	if !f[0].End.Equal(s.at(10 * time.Minute)) {
		t.Fatalf("old interval end %v, want its last known end 09:10", f[0].End)
	}
}

func TestStopClosesOpenInterval(t *testing.T) {
	s := newSim(t)
	s.start(false, false, false)
	s.run(2*time.Minute+3*time.Second, true)
	s.collect(s.m.Stop(s.now))
	if f := s.final(); len(f) != 1 || !f[0].End.Equal(s.now) {
		t.Fatalf("stop: %+v", f)
	}
}
