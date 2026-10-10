// Package health builds the agent's health records (contract/events.md): one
// every 5 minutes and one with expected_stop before a suspend or shutdown.
package health

import (
	"fmt"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/events"
	"github.com/AndDev741/beConscient/desktop/internal/queue"
)

// Period is how often the agent writes a health record and how often the
// extension sends a heartbeat.
const Period = 5 * time.Minute

// HeartbeatStale is three missed heartbeats.
const HeartbeatStale = 3 * Period

// SensorForBrowser maps a heartbeat's browser to its sensor name.
func SensorForBrowser(browser string) (string, bool) {
	switch browser {
	case "chrome":
		return events.SensorExtensionChrome, true
	case "brave":
		return events.SensorExtensionBrave, true
	}
	return "", false
}

// ExtensionSensor reports one extension from its latest heartbeat.
func ExtensionSensor(hb *queue.Heartbeat, now time.Time) events.Sensor {
	if hb == nil {
		return events.Sensor{State: events.StateUnknown, Detail: "no heartbeat yet"}
	}
	s := events.Sensor{LastEventAt: hb.At}
	age := now.Sub(time.UnixMilli(hb.At))
	if age <= HeartbeatStale {
		s.State = events.StateOK
		if hb.Version != "" {
			s.Detail = events.Truncate("version "+hb.Version, 120)
		}
		return s
	}
	s.State = events.StateStopped
	s.Detail = fmt.Sprintf("no heartbeat for %d min", int(age.Minutes()))
	return s
}

// Input is what one health record reports.
type Input struct {
	ID            string
	Now           time.Time
	TZ            string
	TZOffsetMin   int
	ClientVersion string
	Activity      events.Sensor
	Heartbeats    map[string]queue.Heartbeat
	Stats         queue.Stats
	ExpectedStop  bool
}

// Build returns the health event.
func Build(in Input) events.Health {
	sensors := map[string]events.Sensor{events.SensorActivity: in.Activity}
	for _, browser := range []string{"chrome", "brave"} {
		name, _ := SensorForBrowser(browser)
		var hb *queue.Heartbeat
		if h, ok := in.Heartbeats[browser]; ok {
			hb = &h
		}
		sensors[name] = ExtensionSensor(hb, in.Now)
	}
	var oldest int64
	if in.Stats.Pending > 0 && in.Stats.OldestPendingSince > 0 {
		oldest = max(in.Now.UnixMilli()-in.Stats.OldestPendingSince, 0)
	}
	return events.Health{
		ID: in.ID, Type: events.TypeHealth, At: in.Now.UnixMilli(), TZ: in.TZ, TZOffsetMin: in.TZOffsetMin,
		ClientVersion: events.Truncate(in.ClientVersion, 32), Sensors: sensors,
		QueueDepth: in.Stats.Pending, OldestUnsentAgeMs: oldest, ExpectedStop: in.ExpectedStop,
	}
}
