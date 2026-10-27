// Package activitytest provides a fake D-Bus source and a fake clock for
// tests.
package activitytest

import (
	"sync"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/activity"
)

// Clock is a fake wall clock with a separate monotonic reading.
type Clock struct {
	mu   sync.Mutex
	wall time.Time
	mono time.Duration
}

// NewClock starts at t.
func NewClock(t time.Time) *Clock { return &Clock{wall: t} }

// Now implements activity.Clock.
func (c *Clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.wall
}

// Mono implements activity.Clock.
func (c *Clock) Mono() time.Duration {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.mono
}

// Advance moves both clocks.
func (c *Clock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.wall = c.wall.Add(d)
	c.mono += d
}

// JumpWall moves only the wall clock (an NTP step or a manual change).
func (c *Clock) JumpWall(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.wall = c.wall.Add(d)
}

// Source is a fake activity.Source and activity.SleepDelayer. Times are
// computed from the clock: input happens when Input is called.
type Source struct {
	mu        sync.Mutex
	Clock     *Clock
	lastInput time.Time

	ScreenSaver    bool
	ScreenSaverErr error
	Locked         bool
	LockedErr      error
	Inhibited      bool
	InhibitedErr   error
	Media          bool
	MediaErr       error
	IdleErr        error

	InhibitedCalls int
	DelayHeld      bool
	DelayTaken     int
	DelayReleased  int

	Sigs chan activity.Signal

	// OnRelease runs when the sleep delay is released, before it returns.
	OnRelease func()
}

// NewSource returns a source whose last input is now.
func NewSource(c *Clock) *Source {
	return &Source{Clock: c, lastInput: c.Now(), Sigs: make(chan activity.Signal, 16)}
}

// Input records keyboard or mouse input now.
func (s *Source) Input() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.lastInput = s.Clock.Now()
}

// SetLastInput sets the last input explicitly.
func (s *Source) SetLastInput(t time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.lastInput = t
}

// Set changes a field under the lock.
func (s *Source) Set(fn func(s *Source)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	fn(s)
}

// IdleTime implements activity.Source.
func (s *Source) IdleTime() (time.Duration, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.IdleErr != nil {
		return 0, s.IdleErr
	}
	return s.Clock.Now().Sub(s.lastInput), nil
}

// ScreenSaverActive implements activity.Source.
func (s *Source) ScreenSaverActive() (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.ScreenSaver, s.ScreenSaverErr
}

// LockedHint implements activity.Source.
func (s *Source) LockedHint() (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.Locked, s.LockedErr
}

// IdleInhibited implements activity.Source.
func (s *Source) IdleInhibited() (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.InhibitedCalls++
	return s.Inhibited, s.InhibitedErr
}

// MediaPlaying implements activity.Source.
func (s *Source) MediaPlaying() (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.Media, s.MediaErr
}

// Signals implements activity.Source.
func (s *Source) Signals() <-chan activity.Signal { return s.Sigs }

// TakeSleepDelay implements activity.SleepDelayer.
func (s *Source) TakeSleepDelay() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.DelayHeld {
		s.DelayTaken++
	}
	s.DelayHeld = true
	return nil
}

// ReleaseSleepDelay implements activity.SleepDelayer.
func (s *Source) ReleaseSleepDelay() {
	s.mu.Lock()
	held := s.DelayHeld
	if held {
		s.DelayReleased++
	}
	s.DelayHeld = false
	hook := s.OnRelease
	s.mu.Unlock()
	if held && hook != nil {
		hook()
	}
}
