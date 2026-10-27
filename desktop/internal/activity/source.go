package activity

import "time"

// SignalKind names a session or system event the monitor reacts to.
type SignalKind int

// Signals delivered by a Source.
const (
	// SigScreenSaver is org.gnome.ScreenSaver ActiveChanged; Value is the new
	// active (locked or shielded) flag.
	SigScreenSaver SignalKind = iota
	// SigLockedHint is a change of the logind session's LockedHint.
	SigLockedHint
	// SigInhibitors is InhibitorAdded or InhibitorRemoved; the monitor
	// re-reads IsInhibited(8). The inhibitor's id is never read.
	SigInhibitors
	// SigPrepareForSleep is logind PrepareForSleep; Value is true before a
	// suspend and false after the resume.
	SigPrepareForSleep
	// SigPrepareForShutdown is logind PrepareForShutdown; Value is true
	// before a shutdown and false when it was cancelled.
	SigPrepareForShutdown
)

func (k SignalKind) String() string {
	switch k {
	case SigScreenSaver:
		return "ScreenSaver.ActiveChanged"
	case SigLockedHint:
		return "login1.Session.LockedHint"
	case SigInhibitors:
		return "SessionManager.Inhibitor"
	case SigPrepareForSleep:
		return "login1.PrepareForSleep"
	case SigPrepareForShutdown:
		return "login1.PrepareForShutdown"
	}
	return "unknown"
}

// Signal is one event from a Source.
type Signal struct {
	Kind  SignalKind
	Value bool
}

// Source is everything the monitor reads from the desktop. The real
// implementation talks to D-Bus; tests use a fake.
type Source interface {
	// IdleTime is org.gnome.Mutter.IdleMonitor.GetIdletime.
	IdleTime() (time.Duration, error)
	// ScreenSaverActive is org.gnome.ScreenSaver.GetActive.
	ScreenSaverActive() (bool, error)
	// LockedHint is the logind session's LockedHint (fallback lock source).
	LockedHint() (bool, error)
	// IdleInhibited is org.gnome.SessionManager.IsInhibited(8).
	IdleInhibited() (bool, error)
	// MediaPlaying reports whether any MPRIS player's PlaybackStatus is
	// Playing. Player names are never sent.
	MediaPlaying() (bool, error)
	// Signals delivers session and system events.
	Signals() <-chan Signal
}

// SleepDelayer takes and releases a logind "delay" inhibitor for sleep and
// shutdown, so the agent can write the open interval before the machine
// stops.
type SleepDelayer interface {
	TakeSleepDelay() error
	ReleaseSleepDelay()
}

// Clock gives the wall clock and a monotonic reading. A difference between
// the two over one poll reveals a wall-clock jump.
type Clock interface {
	Now() time.Time
	Mono() time.Duration
}

type realClock struct{ base time.Time }

// RealClock returns the system clock. Now has its monotonic reading stripped
// so that every comparison uses the wall clock.
func RealClock() Clock { return realClock{base: time.Now()} }

func (c realClock) Now() time.Time      { return time.Now().Round(0) }
func (c realClock) Mono() time.Duration { return time.Since(c.base) }
