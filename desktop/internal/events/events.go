// Package events defines the events the desktop agent sends, exactly as
// contract/events.md allows them. Every event is built from these structs, so
// no other field (window title, app name, inhibitor id or reason, URL) can be
// serialised.
package events

import (
	"encoding/json"
	"unicode/utf8"
)

// Event types produced by the desktop agent.
const (
	TypeActivity = "desktop.activity"
	TypeSite     = "browser.site"
	TypeHealth   = "health"
)

// Activity reasons.
const (
	ReasonInput     = "input"
	ReasonInhibitor = "inhibitor"
	ReasonPlaying   = "playing"
)

// Sensor names reported by the desktop.
const (
	SensorActivity        = "activity"
	SensorExtensionChrome = "extension_chrome"
	SensorExtensionBrave  = "extension_brave"
)

// Sensor states.
const (
	StateOK       = "ok"
	StateStopped  = "stopped"
	StateDisabled = "disabled"
	StateUnknown  = "unknown"
)

// Activity is a desktop.activity interval revision.
type Activity struct {
	ID          string `json:"id"`
	Type        string `json:"type"`
	Start       int64  `json:"start"`
	End         int64  `json:"end"`
	Rev         int    `json:"rev"`
	Reason      string `json:"reason"`
	TZ          string `json:"tz,omitempty"`
	TZOffsetMin int    `json:"tz_offset_min"`
}

// Site is a browser.site interval revision. It never carries `focused`, a URL
// or a LinkedIn title.
type Site struct {
	ID          string `json:"id"`
	Type        string `json:"type"`
	Start       int64  `json:"start"`
	End         int64  `json:"end"`
	Rev         int    `json:"rev"`
	Site        string `json:"site"`
	Browser     string `json:"browser"`
	Playing     bool   `json:"playing"`
	Title       string `json:"title,omitempty"`
	PageKind    string `json:"page_kind,omitempty"`
	TZ          string `json:"tz,omitempty"`
	TZOffsetMin *int   `json:"tz_offset_min,omitempty"`
}

// Sensor is one entry of a health record's sensors map.
type Sensor struct {
	State       string `json:"state"`
	Detail      string `json:"detail,omitempty"`
	LastEventAt int64  `json:"last_event_at,omitempty"`
}

// Health is a health point event.
type Health struct {
	ID                string            `json:"id"`
	Type              string            `json:"type"`
	At                int64             `json:"at"`
	TZ                string            `json:"tz,omitempty"`
	TZOffsetMin       int               `json:"tz_offset_min"`
	ClientVersion     string            `json:"client_version"`
	Sensors           map[string]Sensor `json:"sensors"`
	QueueDepth        int               `json:"queue_depth"`
	OldestUnsentAgeMs int64             `json:"oldest_unsent_age_ms"`
	ExpectedStop      bool              `json:"expected_stop"`
}

// Allowed lists, per type, every top-level field the contract allows.
var Allowed = map[string]map[string]bool{
	TypeActivity: set("id", "type", "start", "end", "rev", "tz", "tz_offset_min", "reason"),
	TypeSite:     set("id", "type", "start", "end", "rev", "tz", "tz_offset_min", "site", "browser", "playing", "title", "page_kind"),
	TypeHealth: set("id", "type", "at", "tz", "tz_offset_min", "client_version", "sensors", "watermark",
		"queue_depth", "oldest_unsent_age_ms", "home_package", "expected_stop"),
}

// AllowedSensorFields lists the fields of one sensor entry.
var AllowedSensorFields = set("state", "detail", "last_event_at")

func set(keys ...string) map[string]bool {
	m := make(map[string]bool, len(keys))
	for _, k := range keys {
		m[k] = true
	}
	return m
}

// Disallowed returns the top-level fields of a serialised event that the
// contract does not allow for its type (all of them for an unknown type).
func Disallowed(raw []byte) ([]string, error) {
	var m map[string]json.RawMessage
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, err
	}
	var typ string
	_ = json.Unmarshal(m["type"], &typ)
	allowed := Allowed[typ]
	var bad []string
	for k := range m {
		if !allowed[k] {
			bad = append(bad, k)
		}
	}
	if typ == TypeHealth {
		var sensors map[string]map[string]json.RawMessage
		if err := json.Unmarshal(m["sensors"], &sensors); err == nil {
			for name, s := range sensors {
				for k := range s {
					if !AllowedSensorFields[k] {
						bad = append(bad, "sensors."+name+"."+k)
					}
				}
			}
		}
	}
	return bad, nil
}

// Truncate cuts s to at most max characters (runes) and drops invalid UTF-8.
func Truncate(s string, max int) string {
	if !utf8.ValidString(s) {
		s = string([]rune(s))
	}
	if utf8.RuneCountInString(s) <= max {
		return s
	}
	r := []rune(s)
	return string(r[:max])
}
