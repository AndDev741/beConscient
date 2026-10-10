package health_test

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/events"
	"github.com/AndDev741/beConscient/desktop/internal/health"
	"github.com/AndDev741/beConscient/desktop/internal/queue"
)

var now = time.Date(2026, 10, 10, 9, 0, 0, 0, time.UTC)

func TestExtensionHeartbeatBecomesSensor(t *testing.T) {
	h := health.Build(health.Input{
		ID: "0192f3a1-0000-7000-8000-000000000001", Now: now, ClientVersion: "0.1.0",
		Activity: events.Sensor{State: events.StateOK},
		Heartbeats: map[string]queue.Heartbeat{
			"chrome": {Browser: "chrome", At: now.Add(-4 * time.Minute).UnixMilli(), Version: "0.1.0"},
			"brave":  {Browser: "brave", At: now.Add(-47 * time.Minute).UnixMilli(), Version: "0.1.0"},
		},
		Stats: queue.Stats{Pending: 3, OldestPendingSince: now.Add(-90 * time.Second).UnixMilli()},
	})
	c, b := h.Sensors[events.SensorExtensionChrome], h.Sensors[events.SensorExtensionBrave]
	if c.State != events.StateOK || c.LastEventAt != now.Add(-4*time.Minute).UnixMilli() {
		t.Fatalf("chrome %+v", c)
	}
	if b.State != events.StateStopped || b.Detail != "no heartbeat for 47 min" {
		t.Fatalf("brave %+v", b)
	}
	if h.QueueDepth != 3 || h.OldestUnsentAgeMs != 90000 || h.ExpectedStop {
		t.Fatalf("health %+v", h)
	}
	raw, _ := json.Marshal(h)
	if bad, _ := events.Disallowed(raw); len(bad) > 0 {
		t.Fatalf("forbidden fields %v", bad)
	}
}

func TestNoHeartbeatIsUnknown(t *testing.T) {
	h := health.Build(health.Input{Now: now, Activity: events.Sensor{State: events.StateOK}, ExpectedStop: true})
	for _, name := range []string{events.SensorExtensionChrome, events.SensorExtensionBrave} {
		if h.Sensors[name].State != events.StateUnknown {
			t.Fatalf("%s %+v", name, h.Sensors[name])
		}
	}
	if !h.ExpectedStop || h.Type != "health" {
		t.Fatalf("%+v", h)
	}
	if _, ok := health.SensorForBrowser("firefox"); ok {
		t.Fatal("only Chrome and Brave have sensors")
	}
}
