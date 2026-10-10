package activity

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"syscall"
	"time"

	"github.com/godbus/dbus/v5"
)

// D-Bus names used by the agent. Only read calls and signal subscriptions,
// plus the logind delay inhibitor taken by the running agent.
const (
	mutterIdleDest  = "org.gnome.Mutter.IdleMonitor"
	mutterIdlePath  = "/org/gnome/Mutter/IdleMonitor/Core"
	mutterIdleIface = "org.gnome.Mutter.IdleMonitor"

	screenSaverDest  = "org.gnome.ScreenSaver"
	screenSaverPath  = "/org/gnome/ScreenSaver"
	screenSaverIface = "org.gnome.ScreenSaver"

	sessionMgrDest  = "org.gnome.SessionManager"
	sessionMgrPath  = "/org/gnome/SessionManager"
	sessionMgrIface = "org.gnome.SessionManager"
	// inhibitIdle is GSM_INHIBITOR_FLAG_IDLE.
	inhibitIdle uint32 = 8

	login1Dest         = "org.freedesktop.login1"
	login1Path         = "/org/freedesktop/login1"
	login1Manager      = "org.freedesktop.login1.Manager"
	login1SessionIface = "org.freedesktop.login1.Session"
	login1UserIface    = "org.freedesktop.login1.User"
	propsIface         = "org.freedesktop.DBus.Properties"

	callTimeout = 3 * time.Second
)

// DBusSource reads the GNOME session bus and the logind system bus.
type DBusSource struct {
	session *dbus.Conn
	system  *dbus.Conn
	log     *slog.Logger

	sessionPath dbus.ObjectPath
	signals     chan Signal

	mu        sync.Mutex
	inhibitFD int
}

// NewDBusSource connects to the session bus (required) and the system bus
// (optional: without it suspend is detected only as a clock jump and the
// LockedHint fallback is unavailable). Every failure is logged.
func NewDBusSource(log *slog.Logger, subscribe bool) (*DBusSource, error) {
	if log == nil {
		log = slog.Default()
	}
	s := &DBusSource{log: log, signals: make(chan Signal, 64), inhibitFD: -1}
	var err error
	s.session, err = dbus.ConnectSessionBus()
	if err != nil {
		return nil, fmt.Errorf("D-Bus session bus: %w", err)
	}
	s.system, err = dbus.ConnectSystemBus()
	if err != nil {
		log.Error("D-Bus: system bus unavailable; suspend is detected only as a clock jump", "err", err)
		s.system = nil
	} else {
		s.sessionPath = s.findSession()
	}
	if subscribe {
		if err := s.subscribe(); err != nil {
			s.Close()
			return nil, err
		}
	}
	return s, nil
}

// findSession resolves the logind session object for LockedHint. Under
// systemd --user the agent is not inside a session, so it falls back to the
// user's display session.
func (s *DBusSource) findSession() dbus.ObjectPath {
	var p dbus.ObjectPath
	mgr := s.system.Object(login1Dest, login1Path)
	if err := mgr.Call(login1Manager+".GetSession", 0, "auto").Store(&p); err == nil && p.IsValid() {
		return p
	}
	user := s.system.Object(login1Dest, dbus.ObjectPath(login1Path+"/user/self"))
	v, err := user.GetProperty(login1UserIface + ".Display")
	if err == nil {
		if parts, ok := v.Value().([]interface{}); ok && len(parts) == 2 {
			if op, ok := parts[1].(dbus.ObjectPath); ok && op.IsValid() && op != "/" {
				return op
			}
		}
	}
	s.log.Warn("D-Bus: no logind session found; the LockedHint fallback is unavailable", "err", err)
	return ""
}

func (s *DBusSource) subscribe() error {
	matches := []struct {
		conn *dbus.Conn
		opts []dbus.MatchOption
	}{
		{s.session, []dbus.MatchOption{dbus.WithMatchObjectPath(screenSaverPath), dbus.WithMatchInterface(screenSaverIface), dbus.WithMatchMember("ActiveChanged")}},
		{s.session, []dbus.MatchOption{dbus.WithMatchObjectPath(sessionMgrPath), dbus.WithMatchInterface(sessionMgrIface), dbus.WithMatchMember("InhibitorAdded")}},
		{s.session, []dbus.MatchOption{dbus.WithMatchObjectPath(sessionMgrPath), dbus.WithMatchInterface(sessionMgrIface), dbus.WithMatchMember("InhibitorRemoved")}},
	}
	if s.system != nil {
		matches = append(matches,
			struct {
				conn *dbus.Conn
				opts []dbus.MatchOption
			}{s.system, []dbus.MatchOption{dbus.WithMatchObjectPath(login1Path), dbus.WithMatchInterface(login1Manager), dbus.WithMatchMember("PrepareForSleep")}},
			struct {
				conn *dbus.Conn
				opts []dbus.MatchOption
			}{s.system, []dbus.MatchOption{dbus.WithMatchObjectPath(login1Path), dbus.WithMatchInterface(login1Manager), dbus.WithMatchMember("PrepareForShutdown")}},
		)
		if s.sessionPath != "" {
			matches = append(matches, struct {
				conn *dbus.Conn
				opts []dbus.MatchOption
			}{s.system, []dbus.MatchOption{dbus.WithMatchObjectPath(s.sessionPath), dbus.WithMatchInterface(propsIface), dbus.WithMatchMember("PropertiesChanged")}})
		}
	}
	for _, m := range matches {
		if err := m.conn.AddMatchSignal(m.opts...); err != nil {
			return fmt.Errorf("D-Bus: subscribe: %w", err)
		}
	}
	sessCh := make(chan *dbus.Signal, 64)
	s.session.Signal(sessCh)
	go s.translate(sessCh, "session")
	if s.system != nil {
		sysCh := make(chan *dbus.Signal, 64)
		s.system.Signal(sysCh)
		go s.translate(sysCh, "system")
	}
	return nil
}

func (s *DBusSource) translate(ch chan *dbus.Signal, bus string) {
	for sig := range ch {
		if sig == nil {
			continue
		}
		out, ok := s.decode(sig)
		if !ok {
			continue
		}
		select {
		case s.signals <- out:
		default:
			s.log.Error("D-Bus: signal queue full; dropping signal", "signal", out.Kind.String())
		}
	}
	s.log.Error("D-Bus: connection closed; no more signals", "bus", bus)
}

func (s *DBusSource) decode(sig *dbus.Signal) (Signal, bool) {
	boolArg := func() (bool, bool) {
		if len(sig.Body) < 1 {
			return false, false
		}
		v, ok := sig.Body[0].(bool)
		return v, ok
	}
	switch sig.Name {
	case screenSaverIface + ".ActiveChanged":
		if v, ok := boolArg(); ok {
			return Signal{Kind: SigScreenSaver, Value: v}, true
		}
	case sessionMgrIface + ".InhibitorAdded", sessionMgrIface + ".InhibitorRemoved":
		// The argument is the inhibitor's object path. It is never read.
		return Signal{Kind: SigInhibitors}, true
	case login1Manager + ".PrepareForSleep":
		if v, ok := boolArg(); ok {
			return Signal{Kind: SigPrepareForSleep, Value: v}, true
		}
	case login1Manager + ".PrepareForShutdown":
		if v, ok := boolArg(); ok {
			return Signal{Kind: SigPrepareForShutdown, Value: v}, true
		}
	case propsIface + ".PropertiesChanged":
		if sig.Path != s.sessionPath || len(sig.Body) < 2 {
			return Signal{}, false
		}
		if iface, _ := sig.Body[0].(string); iface != login1SessionIface {
			return Signal{}, false
		}
		changed, _ := sig.Body[1].(map[string]dbus.Variant)
		if v, ok := changed["LockedHint"]; ok {
			if b, ok := v.Value().(bool); ok {
				return Signal{Kind: SigLockedHint, Value: b}, true
			}
		}
	}
	return Signal{}, false
}

func (s *DBusSource) call(conn *dbus.Conn, dest, path, method string, out interface{}, args ...interface{}) error {
	if conn == nil {
		return errors.New("bus not connected")
	}
	ctx, cancel := context.WithTimeout(context.Background(), callTimeout)
	defer cancel()
	return conn.Object(dest, dbus.ObjectPath(path)).CallWithContext(ctx, method, 0, args...).Store(out)
}

// IdleTime implements Source.
func (s *DBusSource) IdleTime() (time.Duration, error) {
	var ms uint64
	if err := s.call(s.session, mutterIdleDest, mutterIdlePath, mutterIdleIface+".GetIdletime", &ms); err != nil {
		return 0, err
	}
	return time.Duration(ms) * time.Millisecond, nil
}

// ScreenSaverActive implements Source.
func (s *DBusSource) ScreenSaverActive() (bool, error) {
	var v bool
	err := s.call(s.session, screenSaverDest, screenSaverPath, screenSaverIface+".GetActive", &v)
	return v, err
}

// IdleInhibited implements Source.
func (s *DBusSource) IdleInhibited() (bool, error) {
	var v bool
	err := s.call(s.session, sessionMgrDest, sessionMgrPath, sessionMgrIface+".IsInhibited", &v, inhibitIdle)
	return v, err
}

// LockedHint implements Source.
func (s *DBusSource) LockedHint() (bool, error) {
	if s.system == nil || s.sessionPath == "" {
		return false, errors.New("no logind session")
	}
	var v dbus.Variant
	if err := s.call(s.system, login1Dest, string(s.sessionPath), propsIface+".Get", &v, login1SessionIface, "LockedHint"); err != nil {
		return false, err
	}
	b, ok := v.Value().(bool)
	if !ok {
		return false, errors.New("LockedHint is not a boolean")
	}
	return b, nil
}

// Signals implements Source.
func (s *DBusSource) Signals() <-chan Signal { return s.signals }

// TakeSleepDelay takes a logind delay inhibitor for sleep and shutdown, so
// the agent gets a few seconds to write the open interval.
func (s *DBusSource) TakeSleepDelay() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.inhibitFD >= 0 {
		return nil
	}
	if s.system == nil {
		return errors.New("system bus not connected")
	}
	var fd dbus.UnixFD
	err := s.call(s.system, login1Dest, login1Path, login1Manager+".Inhibit", &fd,
		"sleep:shutdown", "beConscient", "Save the open screen-time interval", "delay")
	if err != nil {
		return fmt.Errorf("D-Bus: logind Inhibit: %w", err)
	}
	s.inhibitFD = int(fd)
	return nil
}

// ReleaseSleepDelay closes the inhibitor, letting the suspend proceed.
func (s *DBusSource) ReleaseSleepDelay() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.inhibitFD >= 0 {
		syscall.Close(s.inhibitFD)
		s.inhibitFD = -1
	}
}

// Close releases the inhibitor and closes both connections.
func (s *DBusSource) Close() error {
	s.ReleaseSleepDelay()
	if s.system != nil {
		s.system.Close()
	}
	if s.session != nil {
		s.session.Close()
	}
	return nil
}

// SessionPath returns the logind session used for LockedHint.
func (s *DBusSource) SessionPath() string { return string(s.sessionPath) }
