// Package sites turns the extension's site intervals into browser.site
// events (contract/native-messaging.md).
//
//   - A focused interval is kept as it is, minus the focused flag.
//   - An unfocused interval counts only while a video plays and only for the
//     parts where the agent was Passive (no input for 5 minutes), so music
//     playing behind an editor while typing stays computer time. Each such
//     part becomes its own interval with a deterministic id derived from the
//     site interval and the Passive interval it overlaps.
//   - An unfocused interval without a playing video never counts.
//
// Only whitelisted fields are copied: never a URL, a LinkedIn title, or any
// site other than YouTube and LinkedIn.
package sites

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/events"
	"github.com/AndDev741/beConscient/desktop/internal/queue"
	"github.com/AndDev741/beConscient/desktop/internal/uuidv7"
)

// Field limits from contract/events.md.
const (
	MaxTitle = 300
	MaxTZ    = 64
)

var (
	validSite     = map[string]bool{"youtube": true, "linkedin": true}
	validBrowser  = map[string]bool{"chrome": true, "brave": true, "other": true}
	validPageKind = map[string]bool{"feed": true, "messaging": true, "profile": true, "jobs": true, "other": true}
)

// Message is a "site" message from the extension. Unknown fields (a URL, for
// example) are ignored by the decoder and never stored.
type Message struct {
	T           string  `json:"t"`
	ID          string  `json:"id"`
	Rev         int     `json:"rev"`
	Site        string  `json:"site"`
	Browser     string  `json:"browser"`
	Start       int64   `json:"start"`
	End         int64   `json:"end"`
	Playing     bool    `json:"playing"`
	Focused     bool    `json:"focused"`
	Title       *string `json:"title"`
	PageKind    *string `json:"page_kind"`
	TZ          string  `json:"tz"`
	TZOffsetMin *int    `json:"tz_offset_min"`
}

// Raw is a validated, sanitised site interval.
type Raw struct {
	ID          string `json:"id"`
	Rev         int    `json:"rev"`
	Site        string `json:"site"`
	Browser     string `json:"browser"`
	Start       int64  `json:"start"`
	End         int64  `json:"end"`
	Playing     bool   `json:"playing"`
	Focused     bool   `json:"focused"`
	Title       string `json:"title,omitempty"`
	PageKind    string `json:"page_kind,omitempty"`
	TZ          string `json:"tz,omitempty"`
	TZOffsetMin *int   `json:"tz_offset_min,omitempty"`
}

// ErrDropped marks a message that is acknowledged but never stored.
var ErrDropped = errors.New("site interval dropped")

// Parse validates and sanitises a site message. An error wrapping ErrDropped
// means the message is invalid or not allowed; it should still be
// acknowledged so the extension stops resending it.
func Parse(data []byte) (Raw, error) {
	var m Message
	if err := json.Unmarshal(data, &m); err != nil {
		return Raw{}, fmt.Errorf("%w: %v", ErrDropped, err)
	}
	return FromMessage(m)
}

// FromMessage validates and sanitises a decoded site message.
func FromMessage(m Message) (Raw, error) {
	drop := func(why string) (Raw, error) { return Raw{ID: m.ID}, fmt.Errorf("%w: %s", ErrDropped, why) }
	if m.T != "site" {
		return drop("not a site message")
	}
	if !uuidv7.Valid(m.ID) {
		return drop("invalid id")
	}
	if m.Rev < 1 {
		return drop("invalid rev")
	}
	if !validSite[m.Site] {
		return drop("site not allowed")
	}
	if m.Start <= 0 || m.End < m.Start {
		return drop("invalid times")
	}
	r := Raw{ID: m.ID, Rev: m.Rev, Site: m.Site, Browser: m.Browser, Start: m.Start, End: m.End,
		Playing: m.Playing, Focused: m.Focused}
	if !validBrowser[r.Browser] {
		r.Browser = "other"
	}
	switch m.Site {
	case "youtube":
		if m.Title != nil {
			r.Title = events.Truncate(*m.Title, MaxTitle)
		}
	case "linkedin":
		// LinkedIn titles never leave the machine; only the page kind does.
		if m.PageKind != nil {
			r.PageKind = *m.PageKind
			if !validPageKind[r.PageKind] {
				r.PageKind = "other"
			}
		}
	}
	if len(m.TZ) <= MaxTZ {
		r.TZ = m.TZ
	}
	if m.TZOffsetMin != nil && *m.TZOffsetMin >= -24*60 && *m.TZOffsetMin <= 24*60 {
		v := *m.TZOffsetMin
		r.TZOffsetMin = &v
	}
	return r, nil
}

// Event builds the browser.site event for a part of r.
func (r Raw) Event(id string, rev int, start, end int64) events.Site {
	return events.Site{
		ID: id, Type: events.TypeSite, Start: start, End: end, Rev: rev,
		Site: r.Site, Browser: r.Browser, Playing: r.Playing, Title: r.Title, PageKind: r.PageKind,
		TZ: r.TZ, TZOffsetMin: r.TZOffsetMin,
	}
}

func toQueue(ev events.Site) (queue.Event, error) {
	payload, err := json.Marshal(ev)
	if err != nil {
		return queue.Event{}, err
	}
	return queue.Event{ID: ev.ID, Rev: ev.Rev, Type: events.TypeSite, T0: ev.Start, T1: ev.End, Payload: payload}, nil
}

// PieceID is the deterministic id of the part of site interval siteID that
// overlaps Passive interval spanID.
func PieceID(siteID, spanID string, start int64) string {
	return uuidv7.Derived(time.UnixMilli(start), "site-piece", siteID, spanID)
}

type piece struct {
	id, spanID string
	start, end int64
}

// pieces intersects r with the Passive spans.
func pieces(r Raw, spans []queue.Span) []piece {
	var out []piece
	seen := map[string]bool{}
	for _, s := range spans {
		start, end := max(r.Start, s.Start), min(r.End, s.End)
		if end <= start || seen[s.ID] {
			continue
		}
		seen[s.ID] = true
		// Both starts never change, so the id is stable across revisions.
		out = append(out, piece{id: PieceID(r.ID, s.ID, start), spanID: s.ID, start: start, end: end})
	}
	return out
}

// Store writes one site interval in a single transaction. open, when not
// nil, is the agent's open Passive interval with its current end, which may
// be newer than the stored revision.
func Store(q *queue.Queue, r Raw, open *queue.Span) error {
	return q.Tx(func(t *queue.Tx) error { return StoreTx(t, r, open) })
}

// StoreTx is Store inside an existing transaction.
func StoreTx(t *queue.Tx, r Raw, open *queue.Span) error {
	if r.Focused {
		ev, err := toQueue(r.Event(r.ID, r.Rev, r.Start, r.End))
		if err != nil {
			return err
		}
		return t.Put(ev)
	}
	if !r.Playing {
		return nil
	}
	prev, ok, err := t.SiteRaw(r.ID)
	if err != nil {
		return err
	}
	if ok && prev.Rev >= r.Rev {
		return nil
	}
	body, err := json.Marshal(r)
	if err != nil {
		return err
	}
	if err := t.PutSiteRaw(queue.RawSite{ID: r.ID, Rev: r.Rev, T0: r.Start, T1: r.End, Body: body}); err != nil {
		return err
	}
	return reconcile(t, r, open)
}

// Reconcile recomputes the pieces of every stored unfocused interval that
// overlaps span. The agent calls it whenever it writes a Passive interval,
// because a Passive interval starts at the last input, which may be before
// site revisions that were already processed.
func Reconcile(q *queue.Queue, span queue.Span) error {
	return q.Tx(func(t *queue.Tx) error {
		raws, err := t.SiteRawsOverlapping(span.Start, span.End)
		if err != nil {
			return err
		}
		for _, rs := range raws {
			var r Raw
			if err := json.Unmarshal(rs.Body, &r); err != nil {
				continue
			}
			if err := reconcile(t, r, &span); err != nil {
				return err
			}
		}
		return nil
	})
}

func reconcile(t *queue.Tx, r Raw, open *queue.Span) error {
	spans, err := t.PassiveSpans(r.Start, r.End)
	if err != nil {
		return err
	}
	if open != nil {
		replaced := false
		for i := range spans {
			if spans[i].ID == open.ID {
				spans[i] = *open
				replaced = true
			}
		}
		if !replaced {
			spans = append(spans, *open)
		}
	}
	stored, err := t.PiecesForSite(r.ID)
	if err != nil {
		return err
	}
	byID := map[string]queue.Piece{}
	for _, p := range stored {
		byID[p.ID] = p
	}
	current := map[string]bool{}
	for _, p := range pieces(r, spans) {
		current[p.id] = true
		if err := writePiece(t, r, p.id, p.spanID, p.start, p.end, byID); err != nil {
			return err
		}
	}
	// A piece that no longer overlaps (the site interval was shortened) is
	// rewritten with zero length so the server drops its time.
	for _, p := range stored {
		if !current[p.ID] && p.T1 > p.T0 {
			if err := writePiece(t, r, p.ID, p.SpanID, p.T0, p.T0, byID); err != nil {
				return err
			}
		}
	}
	return nil
}

func writePiece(t *queue.Tx, r Raw, id, spanID string, start, end int64, stored map[string]queue.Piece) error {
	body, err := json.Marshal(r.Event(id, 0, start, end))
	if err != nil {
		return err
	}
	prev, ok := stored[id]
	if ok && bytes.Equal(prev.Body, body) {
		return nil
	}
	rev := 1
	if ok {
		rev = prev.Rev + 1
	}
	if err := t.PutPiece(queue.Piece{ID: id, SiteID: r.ID, SpanID: spanID, Rev: rev, T0: start, T1: end, Body: body}); err != nil {
		return err
	}
	ev, err := toQueue(r.Event(id, rev, start, end))
	if err != nil {
		return err
	}
	return t.Put(ev)
}
