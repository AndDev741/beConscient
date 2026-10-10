package activity

import (
	"log/slog"
	"strings"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/events"
)

// JumpThreshold is the wall-clock jump (relative to the monotonic clock over
// one poll) that closes the open interval.
const JumpThreshold = 15 * time.Second

// Monitor feeds a Machine from a Source and a Clock. The agent calls Poll
// every 5 seconds, Checkpoint every 5 minutes and Handle for every signal,
// all from one goroutine. Every interval revision the machine emits is passed
// to the out callback in order.
type Monitor struct {
	src   Source
	clock Clock
	m     *Machine
	log   *slog.Logger
	out   func([]Interval)

	lastWall time.Time
	lastMono time.Duration
	havePoll bool

	useLockedHint bool
	lockBroken    bool
	locked        bool
	inhibited     bool
	playing       map[uint64]bool

	idleBroken    bool
	inhibitBroken bool
	lastIdleOK    time.Time
}

// NewMonitor wires a machine to a source. out receives emitted revisions.
func NewMonitor(src Source, clock Clock, cfg Config, newID func(time.Time) string, out func([]Interval), log *slog.Logger) *Monitor {
	if log == nil {
		log = slog.Default()
	}
	return &Monitor{
		src: src, clock: clock, m: NewMachine(cfg, newID), log: log, out: out,
		playing: map[uint64]bool{},
	}
}

func (mo *Monitor) emit(ivs []Interval) {
	if len(ivs) > 0 && mo.out != nil {
		mo.out(ivs)
	}
}

// Start reads the initial lock, inhibitor and idle state and starts the
// machine. When org.gnome.ScreenSaver is unavailable it falls back to the
// logind session's LockedHint.
func (mo *Monitor) Start() {
	now := mo.clock.Now()
	mo.locked = mo.readLock()
	if inh, err := mo.src.IdleInhibited(); err != nil {
		mo.inhibitBroken = true
		mo.log.Error("D-Bus: org.gnome.SessionManager.IsInhibited failed; video and calls will not keep the agent passive", "err", err)
	} else {
		mo.inhibited = inh
	}
	idle, err := mo.src.IdleTime()
	if err != nil {
		mo.idleBroken = true
		mo.log.Error("D-Bus: org.gnome.Mutter.IdleMonitor.GetIdletime failed; nothing will count until it works", "err", err)
		idle = 24 * time.Hour
	} else {
		mo.lastIdleOK = now
	}
	mo.emit(mo.m.Start(now, idle, mo.locked, mo.inhibited, mo.anyPlaying()))
	mo.lastWall, mo.lastMono, mo.havePoll = now, mo.clock.Mono(), true
	mo.log.Info("activity monitor started", "state", mo.m.State().String(), "locked", mo.locked,
		"lock_source", mo.lockSource(), "inhibited", mo.inhibited)
}

func (mo *Monitor) readLock() bool {
	if !mo.useLockedHint {
		v, err := mo.src.ScreenSaverActive()
		if err == nil {
			return v
		}
		mo.useLockedHint = true
		mo.log.Error("D-Bus: org.gnome.ScreenSaver.GetActive failed; falling back to logind LockedHint", "err", err)
	}
	v, err := mo.src.LockedHint()
	if err != nil {
		if !mo.lockBroken {
			mo.log.Error("D-Bus: logind LockedHint failed too; the lock state is unknown and treated as unlocked", "err", err)
		}
		mo.lockBroken = true
		return false
	}
	if mo.lockBroken {
		mo.log.Info("D-Bus: logind LockedHint works again")
	}
	mo.lockBroken = false
	return v
}

func (mo *Monitor) lockSource() string {
	switch {
	case mo.useLockedHint && mo.lockBroken:
		return "none"
	case mo.useLockedHint:
		return "logind LockedHint"
	}
	return "org.gnome.ScreenSaver"
}

// Poll reads the idle time (and the fallback lock state and the inhibitor
// flag) and advances the machine. A wall-clock jump of more than 15 seconds
// closes the open interval.
func (mo *Monitor) Poll() {
	now, mono := mo.clock.Now(), mo.clock.Mono()
	jumped := false
	if mo.havePoll && mo.m.State() != Suspended {
		drift := now.Sub(mo.lastWall) - (mono - mo.lastMono)
		if drift > JumpThreshold || drift < -JumpThreshold {
			jumped = true
			mo.log.Warn("wall clock jumped; closing the open interval", "jump", drift.Round(time.Second).String())
		}
	}
	mo.lastWall, mo.lastMono, mo.havePoll = now, mono, true

	idle, err := mo.src.IdleTime()
	if err != nil {
		if !mo.idleBroken {
			mo.log.Error("D-Bus: org.gnome.Mutter.IdleMonitor.GetIdletime failed; treating it as no input", "err", err)
		}
		mo.idleBroken = true
		idle = now.Sub(mo.m.LastInput())
	} else {
		if mo.idleBroken {
			mo.log.Info("D-Bus: idle monitor works again")
		}
		mo.idleBroken = false
		mo.lastIdleOK = now
	}

	mo.refreshInhibited(now)
	if mo.useLockedHint {
		if v := mo.readLock(); v != mo.locked {
			mo.locked = v
			mo.emit(mo.m.SetLocked(now, v))
		}
	}
	if jumped {
		mo.emit(mo.m.ClockJump(now, idle))
		return
	}
	mo.emit(mo.m.Poll(now, idle))
}

func (mo *Monitor) refreshInhibited(now time.Time) {
	inh, err := mo.src.IdleInhibited()
	if err != nil {
		if !mo.inhibitBroken {
			mo.log.Error("D-Bus: org.gnome.SessionManager.IsInhibited failed", "err", err)
		}
		mo.inhibitBroken = true
		return
	}
	if mo.inhibitBroken {
		mo.log.Info("D-Bus: SessionManager works again")
	}
	mo.inhibitBroken = false
	if inh != mo.inhibited {
		mo.inhibited = inh
		mo.emit(mo.m.SetInhibited(now, inh))
	}
}

// Handle reacts to one signal.
func (mo *Monitor) Handle(sig Signal) {
	now := mo.clock.Now()
	switch sig.Kind {
	case SigScreenSaver:
		if mo.useLockedHint {
			return
		}
		mo.locked = sig.Value
		mo.emit(mo.m.SetLocked(now, sig.Value))
	case SigLockedHint:
		if !mo.useLockedHint {
			return
		}
		mo.locked = sig.Value
		mo.emit(mo.m.SetLocked(now, sig.Value))
	case SigInhibitors:
		mo.refreshInhibited(now)
	case SigPrepareForSleep, SigPrepareForShutdown:
		if sig.Value {
			mo.emit(mo.m.Suspend(now))
			return
		}
		mo.emit(mo.m.Resume(now))
		// The suspend gap is not a clock jump, and a lock signal may have
		// been missed while asleep.
		mo.havePoll = false
		if v := mo.readLock(); v != mo.locked {
			mo.locked = v
			mo.emit(mo.m.SetLocked(now, v))
		}
	}
}

// Checkpoint writes the open interval's current end.
func (mo *Monitor) Checkpoint() { mo.emit(mo.m.Checkpoint(mo.clock.Now())) }

// Stop closes the open interval.
func (mo *Monitor) Stop() { mo.emit(mo.m.Stop(mo.clock.Now())) }

// SetPlaying records the playing flag reported over one extension
// connection. Any playing connection counts as a playing video.
func (mo *Monitor) SetPlaying(conn uint64, playing bool) {
	if playing {
		mo.playing[conn] = true
	} else {
		delete(mo.playing, conn)
	}
	mo.emit(mo.m.SetPlaying(mo.clock.Now(), mo.anyPlaying()))
}

// DropConn forgets a closed extension connection.
func (mo *Monitor) DropConn(conn uint64) { mo.SetPlaying(conn, false) }

func (mo *Monitor) anyPlaying() bool { return len(mo.playing) > 0 }

// State returns the machine state.
func (mo *Monitor) State() State { return mo.m.State() }

// Since returns when the current state was entered.
func (mo *Monitor) Since() time.Time { return mo.m.Since() }

// Open returns the open interval cut at now.
func (mo *Monitor) Open() (Interval, bool) { return mo.m.Open(mo.clock.Now()) }

// Flags returns the latest lock, inhibitor and playing flags.
func (mo *Monitor) Flags() (locked, inhibited, playing bool) {
	return mo.locked, mo.inhibited, mo.anyPlaying()
}

// Sensor returns the activity sensor for the health record.
func (mo *Monitor) Sensor() events.Sensor {
	s := events.Sensor{State: events.StateOK}
	var notes []string
	if mo.idleBroken {
		s.State = events.StateStopped
		notes = append(notes, "idle monitor unavailable")
	}
	switch {
	case mo.useLockedHint && mo.lockBroken:
		notes = append(notes, "lock state unavailable")
	case mo.useLockedHint:
		notes = append(notes, "lock state from logind")
	}
	if mo.inhibitBroken {
		notes = append(notes, "inhibitors unavailable")
	}
	s.Detail = events.Truncate(strings.Join(notes, "; "), 120)
	if !mo.lastIdleOK.IsZero() {
		s.LastEventAt = mo.lastIdleOK.UnixMilli()
	}
	return s
}
