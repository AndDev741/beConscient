// Package activity decides when the computer counts as screen time.
//
// Machine is a pure state machine (no clocks, no D-Bus) that follows the
// diagram in docs/design.md:
//
//	Active  --5 min without input, no video or call-->  Idle
//	Active  --5 min without input, video or call----->  Passive
//	Passive --keyboard or mouse---------------------->  Active
//	Passive --video ends, or 2 h without input------->  Idle
//	Idle    --keyboard or mouse---------------------->  Active
//	Active, Passive, Idle --screen locked------------>  Locked
//	Locked  --unlocked------------------------------->  Active
//	any     --suspend-------------------------------->  Suspended
//	Suspended --resume------------------------------->  Locked
//
// Counted time is Active plus Passive. Every counted stretch is an interval
// with a stable id and a revision number; a new interval starts whenever the
// reason (input, inhibitor, playing) changes. Entering Idle from Active trims
// the open interval back to the last input as a new revision.
//
// Monitor feeds the machine from D-Bus sources and a clock.
package activity

import (
	"time"
)

// State is the agent's activity state.
type State int

// States, in the order of the design diagram.
const (
	Active State = iota
	Passive
	Idle
	Locked
	Suspended
)

func (s State) String() string {
	switch s {
	case Active:
		return "active"
	case Passive:
		return "passive"
	case Idle:
		return "idle"
	case Locked:
		return "locked"
	case Suspended:
		return "suspended"
	}
	return "unknown"
}

// Counted reports whether time in this state is screen time.
func (s State) Counted() bool { return s == Active || s == Passive }

// Reason says why an interval counts.
type Reason string

// Reasons, as in contract/events.md.
const (
	ReasonInput     Reason = "input"
	ReasonInhibitor Reason = "inhibitor"
	ReasonPlaying   Reason = "playing"
)

// Interval is one revision of a counted interval. Revisions of the same
// interval share ID and Start; Rev grows by one with each revision.
type Interval struct {
	ID     string
	Rev    int
	Start  time.Time
	End    time.Time
	Reason Reason
}

// Duration is End minus Start.
func (i Interval) Duration() time.Duration { return i.End.Sub(i.Start) }

// Passive reports whether the interval was counted without input.
func (i Interval) Passive() bool { return i.Reason != ReasonInput }

// Config holds the machine's thresholds.
type Config struct {
	// IdleAfter is how long without input ends Active (5 minutes).
	IdleAfter time.Duration
	// PassiveCap is the most a stretch without input can count (2 hours).
	PassiveCap time.Duration
	// InputTolerance absorbs jitter in "now minus idle time" between polls.
	InputTolerance time.Duration
	// SuspendGrace is how long a poll is ignored after a suspend signal; a
	// poll after that means the resume signal was missed.
	SuspendGrace time.Duration
}

// DefaultConfig returns the thresholds from docs/design.md.
func DefaultConfig() Config {
	return Config{
		IdleAfter:      5 * time.Minute,
		PassiveCap:     2 * time.Hour,
		InputTolerance: time.Second,
		SuspendGrace:   time.Minute,
	}
}

type openInterval struct {
	Interval
	written    bool
	lastEnd    time.Time
	lastReason Reason
}

// Machine is the activity state machine. It is not safe for concurrent use.
type Machine struct {
	cfg   Config
	newID func(time.Time) string

	state     State
	since     time.Time // when the current state was entered
	lastInput time.Time // most recent keyboard or mouse input
	floor     time.Time // no interval may start before this
	locked    bool      // the session reports the screen as locked
	inhibited bool      // a GNOME idle inhibitor is active
	playing   bool      // the extension reports a playing video
	cur       *openInterval
}

// NewMachine returns a machine; call Start before anything else.
func NewMachine(cfg Config, newID func(time.Time) string) *Machine {
	return &Machine{cfg: cfg, newID: newID, state: Idle}
}

// State returns the current state.
func (m *Machine) State() State { return m.state }

// Since returns when the current state was entered.
func (m *Machine) Since() time.Time { return m.since }

// LastInput returns the most recent input the machine knows of.
func (m *Machine) LastInput() time.Time { return m.lastInput }

// Open returns the open interval with its end cut at now. Rev is the last
// written revision (0 when none was written yet).
func (m *Machine) Open(now time.Time) (Interval, bool) {
	if m.cur == nil || !m.state.Counted() {
		return Interval{}, false
	}
	iv := m.cur.Interval
	if end := m.currentEnd(now); end.After(iv.Start) {
		iv.End = end
	}
	return iv, true
}

func (m *Machine) videoOrCall() bool { return m.inhibited || m.playing }

func (m *Machine) passiveReason() Reason {
	if m.playing {
		return ReasonPlaying
	}
	return ReasonInhibitor
}

func (m *Machine) capEnd() time.Time { return m.lastInput.Add(m.cfg.PassiveCap) }

// Start initialises the machine when the agent starts (or after a clock
// jump). Nothing before now is attributed: the first interval starts at now.
func (m *Machine) Start(now time.Time, idle time.Duration, locked, inhibited, playing bool) []Interval {
	var out []Interval
	m.locked, m.inhibited, m.playing = locked, inhibited, playing
	m.restart(now, idle, &out)
	return out
}

func (m *Machine) restart(now time.Time, idle time.Duration, out *[]Interval) {
	if idle < 0 {
		idle = 0
	}
	m.cur = nil
	m.floor = now
	m.lastInput = now.Add(-idle)
	if m.locked {
		m.enter(Locked, now)
		return
	}
	m.enter(Active, now)
	m.open(now, ReasonInput, out)
	m.evalActive(now, out)
}

func (m *Machine) enter(s State, at time.Time) {
	m.state = s
	m.since = at
}

func (m *Machine) open(start time.Time, r Reason, out *[]Interval) {
	if start.Before(m.floor) {
		start = m.floor
	}
	m.cur = &openInterval{Interval: Interval{ID: m.newID(start), Start: start, End: start, Reason: r}}
	m.emit(out)
}

// emit appends a new revision of the open interval when it changed since the
// last revision. A zero-length interval that was never written is skipped.
func (m *Machine) emit(out *[]Interval) {
	c := m.cur
	if c == nil {
		return
	}
	if !c.written && !c.End.After(c.Start) {
		return
	}
	if c.written && c.End.Equal(c.lastEnd) && c.Reason == c.lastReason {
		return
	}
	c.Rev++
	c.written = true
	c.lastEnd = c.End
	c.lastReason = c.Reason
	*out = append(*out, c.Interval)
}

func (m *Machine) extend(end time.Time) {
	if m.cur == nil {
		return
	}
	if end.Before(m.cur.Start) {
		end = m.cur.Start
	}
	m.cur.End = end
}

func (m *Machine) closeAt(end time.Time, out *[]Interval) {
	if m.cur == nil {
		return
	}
	m.extend(end)
	m.emit(out)
	if m.cur.End.After(m.floor) {
		m.floor = m.cur.End
	}
	m.cur = nil
}

// currentEnd is where the open interval ends if it is cut at now.
func (m *Machine) currentEnd(now time.Time) time.Time {
	if m.state == Passive {
		if ce := m.capEnd(); ce.Before(now) {
			return ce
		}
	}
	return now
}

// observe records a new idle-time reading and reports whether it shows input
// newer than the last known input.
func (m *Machine) observe(now time.Time, idle time.Duration) bool {
	if idle < 0 {
		idle = 0
	}
	li := now.Add(-idle)
	newInput := li.After(m.lastInput.Add(m.cfg.InputTolerance))
	if li.After(m.lastInput) {
		m.lastInput = li
	}
	return newInput
}

// Poll handles an idle-time reading taken at now. It is called every 5 s.
func (m *Machine) Poll(now time.Time, idle time.Duration) []Interval {
	var out []Interval
	before := m.lastInput
	newInput := m.observe(now, idle)
	switch m.state {
	case Suspended:
		if now.Sub(m.since) < m.cfg.SuspendGrace {
			return out
		}
		// A poll long after the suspend signal: the resume signal was
		// missed. Behave as after a resume.
		m.enter(Locked, now)
		m.floor = maxTime(m.floor, now)
		return out
	case Locked:
		// Without a lock screen (or after a resume that did not lock), input
		// after entering Locked starts counting again.
		if !m.locked && m.lastInput.After(m.since) {
			m.toActive(m.lastInput, now, &out)
		}
	case Idle:
		if newInput {
			m.toActive(m.lastInput, now, &out)
		}
	case Active:
		m.evalActive(now, &out)
	case Passive:
		if !newInput {
			m.evalPassive(now, &out)
			break
		}
		// Input again: the passive stretch ends at the new input, or at the
		// 2-hour cap measured from the input before it.
		end := m.lastInput
		if ce := before.Add(m.cfg.PassiveCap); ce.Before(end) {
			end = ce
		}
		m.closeAt(end, &out)
		m.toActive(m.lastInput, now, &out)
	}
	return out
}

func (m *Machine) toActive(start, now time.Time, out *[]Interval) {
	m.enter(Active, start)
	m.open(start, ReasonInput, out)
	m.extend(now)
	m.emit(out) // first write as soon as the interval has length
}

// evalActive applies the Active transitions at now.
func (m *Machine) evalActive(now time.Time, out *[]Interval) {
	if now.Sub(m.lastInput) < m.cfg.IdleAfter {
		m.extend(now)
		if m.cur != nil && !m.cur.written {
			m.emit(out)
		}
		return
	}
	// 5 minutes without input: the input interval ends at the last input.
	m.closeAt(m.lastInput, out)
	if m.videoOrCall() {
		m.enter(Passive, m.lastInput)
		m.open(m.lastInput, m.passiveReason(), out)
		m.evalPassive(now, out)
		return
	}
	m.enter(Idle, now)
}

// evalPassive applies the Passive transitions (other than input) at now.
func (m *Machine) evalPassive(now time.Time, out *[]Interval) {
	if ce := m.capEnd(); !now.Before(ce) {
		m.closeAt(ce, out)
		m.enter(Idle, ce)
		return
	}
	if !m.videoOrCall() {
		m.closeAt(now, out)
		m.enter(Idle, now)
		return
	}
	if r := m.passiveReason(); m.cur == nil || m.cur.Reason != r {
		m.closeAt(now, out)
		m.open(now, r, out)
	}
	m.extend(now)
	if m.cur != nil && !m.cur.written {
		m.emit(out)
	}
}

// Checkpoint writes the open interval's current end (every 5 minutes), so a
// crash loses at most that much.
func (m *Machine) Checkpoint(now time.Time) []Interval {
	var out []Interval
	if m.cur == nil || !m.state.Counted() {
		return out
	}
	m.extend(m.currentEnd(now))
	m.emit(&out)
	return out
}

// SetLocked handles a lock or unlock of the session.
func (m *Machine) SetLocked(now time.Time, locked bool) []Interval {
	var out []Interval
	if m.locked == locked {
		return out
	}
	m.locked = locked
	if locked {
		switch m.state {
		case Active, Passive, Idle:
			m.closeAt(m.currentEnd(now), &out)
			m.enter(Locked, now)
		}
		return out
	}
	if m.state == Locked {
		// Unlocking takes input: count from the unlock.
		if now.After(m.lastInput) {
			m.lastInput = now
		}
		m.floor = maxTime(m.floor, now)
		m.enter(Active, now)
		m.open(now, ReasonInput, &out)
	}
	return out
}

// SetInhibited handles a change of the GNOME idle inhibitor flag.
func (m *Machine) SetInhibited(now time.Time, inhibited bool) []Interval {
	if m.inhibited == inhibited {
		return nil
	}
	m.inhibited = inhibited
	return m.reevaluatePassive(now)
}

// SetPlaying handles a change of the extension's playing flag.
func (m *Machine) SetPlaying(now time.Time, playing bool) []Interval {
	if m.playing == playing {
		return nil
	}
	m.playing = playing
	return m.reevaluatePassive(now)
}

func (m *Machine) reevaluatePassive(now time.Time) []Interval {
	var out []Interval
	if m.state == Passive {
		m.evalPassive(now, &out)
	}
	return out
}

// Suspend closes the open interval: nothing counts while suspended or shut
// down.
func (m *Machine) Suspend(now time.Time) []Interval {
	var out []Interval
	if m.state == Suspended {
		return out
	}
	m.closeAt(m.currentEnd(now), &out)
	m.enter(Suspended, now)
	return out
}

// Resume moves from Suspended to Locked; counting resumes at the next unlock
// or input.
func (m *Machine) Resume(now time.Time) []Interval {
	if m.state == Suspended {
		m.enter(Locked, now)
		m.floor = maxTime(m.floor, now)
	}
	return nil
}

// ClockJump closes the open interval at its last known end and restarts the
// machine at now, keeping Locked, Suspended and Idle as they are. idle is a
// fresh idle-time reading, which does not depend on the wall clock.
func (m *Machine) ClockJump(now time.Time, idle time.Duration) []Interval {
	var out []Interval
	if m.cur != nil {
		m.emit(&out)
		m.cur = nil
	}
	switch m.state {
	case Active, Passive:
		m.restart(now, idle, &out)
	case Locked, Idle:
		m.floor = now
		m.lastInput = now.Add(-idle)
		m.since = now
	case Suspended:
		m.floor = now
		m.lastInput = now.Add(-idle)
	}
	return out
}

// Stop closes the open interval when the agent stops.
func (m *Machine) Stop(now time.Time) []Interval {
	var out []Interval
	m.closeAt(m.currentEnd(now), &out)
	return out
}

func maxTime(a, b time.Time) time.Time {
	if b.After(a) {
		return b
	}
	return a
}
