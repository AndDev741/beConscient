package nativehost

import (
	"encoding/json"
	"errors"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/events"
	"github.com/AndDev741/beConscient/desktop/internal/queue"
	"github.com/AndDev741/beConscient/desktop/internal/sites"
)

// Message types from the extension.
const (
	TypeSite      = "site"
	TypePlaying   = "playing"
	TypeHeartbeat = "heartbeat"
	TypeAck       = "ack"
)

// Head is the part of every message used to route it.
type Head struct {
	T string `json:"t"`
}

// PlayingMsg is {"t":"playing"}.
type PlayingMsg struct {
	Playing bool  `json:"playing"`
	At      int64 `json:"at"`
}

// HeartbeatMsg is {"t":"heartbeat"}.
type HeartbeatMsg struct {
	Browser string `json:"browser"`
	Version string `json:"version"`
	At      int64  `json:"at"`
}

// Ack is sent to the extension after site intervals are durably written.
type Ack struct {
	T   string   `json:"t"`
	IDs []string `json:"ids"`
}

// AckJSON encodes an ack.
func AckJSON(ids []string) []byte {
	if ids == nil {
		ids = []string{}
	}
	data, _ := json.Marshal(Ack{T: TypeAck, IDs: ids})
	return data
}

// ParseHead returns the message type.
func ParseHead(msg []byte) (string, error) {
	var h Head
	if err := json.Unmarshal(msg, &h); err != nil {
		return "", err
	}
	if h.T == "" {
		return "", errors.New("message without type")
	}
	return h.T, nil
}

// ackableID returns an id to echo for a dropped message, if it looks sane.
func ackableID(id string) []string {
	if id == "" || len(id) > 64 {
		return nil
	}
	return []string{id}
}

// StoreSite durably writes a site message and returns the ids to ack. A
// message that is not allowed (another site, bad times) is acknowledged
// without being stored, so the extension stops resending it.
func StoreSite(q *queue.Queue, msg []byte, open *queue.Span) ([]string, error) {
	raw, err := sites.Parse(msg)
	if errors.Is(err, sites.ErrDropped) {
		return ackableID(raw.ID), nil
	}
	if err != nil {
		return nil, err
	}
	if err := sites.Store(q, raw, open); err != nil {
		return nil, err
	}
	return []string{raw.ID}, nil
}

// ParseHeartbeat validates a heartbeat. Only Chrome and Brave map to sensors.
func ParseHeartbeat(msg []byte, now time.Time) (HeartbeatMsg, bool) {
	var hb HeartbeatMsg
	if json.Unmarshal(msg, &hb) != nil {
		return hb, false
	}
	if hb.Browser != "chrome" && hb.Browser != "brave" {
		return hb, false
	}
	hb.Version = events.Truncate(hb.Version, 32)
	if hb.At <= 0 || hb.At > now.Add(time.Hour).UnixMilli() {
		hb.At = now.UnixMilli()
	}
	return hb, true
}

// HandleDirect handles one extension message without the agent: site
// intervals and heartbeats go straight to the queue; playing messages only
// matter to a running agent and are dropped. It returns the ids to ack.
func HandleDirect(q *queue.Queue, msg []byte, now time.Time) ([]string, error) {
	t, err := ParseHead(msg)
	if err != nil {
		return nil, nil
	}
	switch t {
	case TypeSite:
		return StoreSite(q, msg, nil)
	case TypeHeartbeat:
		if hb, ok := ParseHeartbeat(msg, now); ok {
			return nil, q.PutHeartbeat(hb.Browser, hb.At, hb.Version)
		}
	}
	return nil, nil
}
