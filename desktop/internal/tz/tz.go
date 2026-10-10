// Package tz reports the IANA name and UTC offset of the machine's time zone.
//
// Go reads the local zone once at start. The agent runs for weeks, so this
// package re-reads the system zone at most once a minute to follow changes.
package tz

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Func returns the IANA zone name (empty when unknown) and the UTC offset in
// minutes at t.
type Func func(t time.Time) (string, int)

var (
	mu      sync.Mutex
	cached  string
	loc     *time.Location
	checked time.Time
)

// Local is the default Func.
func Local(t time.Time) (string, int) {
	mu.Lock()
	defer mu.Unlock()
	if loc == nil || time.Since(checked) > time.Minute {
		name := systemZoneName()
		if name != cached || loc == nil {
			l := time.Local
			if name != "" {
				if ll, err := time.LoadLocation(name); err == nil {
					l = ll
				} else {
					name = ""
				}
			}
			cached, loc = name, l
		}
		checked = time.Now()
	}
	_, off := t.In(loc).Zone()
	return cached, off / 60
}

func systemZoneName() string {
	if v := os.Getenv("TZ"); v != "" {
		v = strings.TrimPrefix(v, ":")
		if !filepath.IsAbs(v) {
			return v
		}
		if n := zoneFromPath(v); n != "" {
			return n
		}
	}
	if target, err := filepath.EvalSymlinks("/etc/localtime"); err == nil {
		if n := zoneFromPath(target); n != "" {
			return n
		}
	}
	if data, err := os.ReadFile("/etc/timezone"); err == nil {
		return strings.TrimSpace(string(data))
	}
	return ""
}

func zoneFromPath(p string) string {
	const marker = "zoneinfo/"
	if i := strings.LastIndex(p, marker); i >= 0 {
		return p[i+len(marker):]
	}
	return ""
}

// Fixed returns a Func for a fixed zone, used by tests.
func Fixed(name string, offsetMin int) Func {
	return func(time.Time) (string, int) { return name, offsetMin }
}
