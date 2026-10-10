// Package queue is the agent's durable SQLite queue
// ($XDG_STATE_HOME/beconscient/queue.db, mode 0600 in a 0700 directory).
//
// It holds every event until the server acknowledges it, the frozen batch
// (sequence number plus the exact ids and revisions, resent unchanged until a
// valid receipt), acknowledged events for 14 days (for "replay"), the local
// rejected table, the extension heartbeats and the raw unfocused site
// intervals used to compute their Passive parts.
package queue

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"time"

	_ "modernc.org/sqlite" // pure Go SQLite driver

	"github.com/AndDev741/beConscient/desktop/internal/paths"
)

// Retention periods.
const (
	AckedRetention    = 14 * 24 * time.Hour
	RawSiteRetention  = 2 * 24 * time.Hour
	RejectedRetention = 30 * 24 * time.Hour
)

const schema = `
CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS events (
  id            TEXT PRIMARY KEY,
  rev           INTEGER NOT NULL,
  type          TEXT NOT NULL,
  tag           TEXT,
  t0            INTEGER NOT NULL,
  t1            INTEGER NOT NULL,
  payload       TEXT NOT NULL,
  pending_since INTEGER,
  acked_rev     INTEGER NOT NULL DEFAULT -1,
  acked_at      INTEGER,
  updated_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS events_by_time ON events(type, t1, t0);
CREATE TABLE IF NOT EXISTS batches (
  installation_id TEXT NOT NULL,
  seq             INTEGER NOT NULL,
  events          TEXT NOT NULL,
  created_at      INTEGER NOT NULL,
  attempts        INTEGER NOT NULL DEFAULT 0,
  last_attempt_at INTEGER,
  PRIMARY KEY (installation_id, seq)
);
CREATE TABLE IF NOT EXISTS batch_items (
  installation_id TEXT NOT NULL,
  seq             INTEGER NOT NULL,
  id              TEXT NOT NULL,
  rev             INTEGER NOT NULL,
  PRIMARY KEY (installation_id, seq, id)
);
CREATE TABLE IF NOT EXISTS rejected (
  id          TEXT NOT NULL,
  rev         INTEGER NOT NULL,
  type        TEXT NOT NULL,
  payload     TEXT NOT NULL,
  reason      TEXT,
  seq         INTEGER,
  rejected_at INTEGER NOT NULL,
  PRIMARY KEY (id, rev)
);
CREATE TABLE IF NOT EXISTS site_raw (
  id         TEXT PRIMARY KEY,
  rev        INTEGER NOT NULL,
  t0         INTEGER NOT NULL,
  t1         INTEGER NOT NULL,
  body       TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS site_pieces (
  id         TEXT PRIMARY KEY,
  site_id    TEXT NOT NULL,
  span_id    TEXT NOT NULL,
  rev        INTEGER NOT NULL,
  t0         INTEGER NOT NULL,
  t1         INTEGER NOT NULL,
  body       TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS site_pieces_by_site ON site_pieces(site_id);
CREATE TABLE IF NOT EXISTS heartbeats (
  browser     TEXT PRIMARY KEY,
  at          INTEGER NOT NULL,
  version     TEXT NOT NULL,
  received_at INTEGER NOT NULL
);
`

// Queue is the SQLite queue. It is safe for concurrent use; other processes
// (the native host when the agent is down) may open the same file.
type Queue struct {
	db   *sql.DB
	path string
	now  func() time.Time
}

// Open opens (creating if needed) the queue at path. The directory is made
// 0700 and the database files 0600.
func Open(path string) (*Queue, error) {
	if err := paths.EnsurePrivateDir(filepath.Dir(path)); err != nil {
		return nil, err
	}
	if err := paths.EnsurePrivateFile(path); err != nil {
		return nil, err
	}
	dsn := "file:" + path +
		"?_pragma=busy_timeout(10000)&_pragma=journal_mode(WAL)&_pragma=synchronous(FULL)&_txlock=immediate"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("queue schema: %w", err)
	}
	q := &Queue{db: db, path: path, now: time.Now}
	q.tightenSidecars()
	return q, nil
}

// tightenSidecars makes sure SQLite's -wal and -shm files are 0600 too.
func (q *Queue) tightenSidecars() {
	for _, suffix := range []string{"-wal", "-shm", "-journal"} {
		p := q.path + suffix
		if fi, err := os.Lstat(p); err == nil && fi.Mode().IsRegular() && fi.Mode().Perm() != paths.FileMode {
			os.Chmod(p, paths.FileMode)
		}
	}
}

// SetNow replaces the clock (tests).
func (q *Queue) SetNow(now func() time.Time) { q.now = now }

// Path returns the database path.
func (q *Queue) Path() string { return q.path }

// Close closes the database.
func (q *Queue) Close() error { return q.db.Close() }

// Event is one queued event revision.
type Event struct {
	ID      string
	Rev     int // 0 for point events
	Type    string
	Tag     string // desktop.activity: the reason
	T0, T1  int64  // start and end (or at, at) in epoch ms
	Payload []byte // the exact JSON sent to the server
}

// Span is a counted Passive stretch, used to keep the matching parts of
// unfocused, playing site intervals.
type Span struct {
	ID         string
	Start, End int64
}

// Tx is a write transaction.
type Tx struct {
	tx  *sql.Tx
	now int64
}

// Tx runs fn in one immediate transaction.
func (q *Queue) Tx(fn func(*Tx) error) error {
	tx, err := q.db.Begin()
	if err != nil {
		return err
	}
	if err := fn(&Tx{tx: tx, now: q.now().UnixMilli()}); err != nil {
		tx.Rollback()
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	q.tightenSidecars()
	return nil
}

// Put writes events in one transaction. A revision lower than or equal to
// the stored one is ignored.
func (q *Queue) Put(evs ...Event) error {
	if len(evs) == 0 {
		return nil
	}
	return q.Tx(func(t *Tx) error {
		for _, ev := range evs {
			if err := t.Put(ev); err != nil {
				return err
			}
		}
		return nil
	})
}

// Put upserts one event revision.
func (t *Tx) Put(ev Event) error {
	if ev.ID == "" || ev.Type == "" || len(ev.Payload) == 0 {
		return errors.New("queue: event needs id, type and payload")
	}
	var tag interface{}
	if ev.Tag != "" {
		tag = ev.Tag
	}
	_, err := t.tx.Exec(`
INSERT INTO events (id, rev, type, tag, t0, t1, payload, pending_since, acked_rev, updated_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, -1, ?)
ON CONFLICT(id) DO UPDATE SET
  rev = excluded.rev, tag = excluded.tag, t0 = excluded.t0, t1 = excluded.t1,
  payload = excluded.payload, updated_at = excluded.updated_at,
  pending_since = CASE WHEN events.pending_since IS NULL OR events.acked_rev >= events.rev
                       THEN excluded.pending_since ELSE events.pending_since END
WHERE excluded.rev > events.rev`,
		ev.ID, ev.Rev, ev.Type, tag, ev.T0, ev.T1, string(ev.Payload), t.now, t.now)
	return err
}

// PassiveSpans returns the stored desktop.activity intervals counted without
// input (reason inhibitor or playing) that overlap [t0, t1].
func (t *Tx) PassiveSpans(t0, t1 int64) ([]Span, error) {
	rows, err := t.tx.Query(`
SELECT id, t0, t1 FROM events
WHERE type = 'desktop.activity' AND tag IN ('inhibitor', 'playing') AND t1 > ? AND t0 < ?
ORDER BY t0`, t0, t1)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Span
	for rows.Next() {
		var s Span
		if err := rows.Scan(&s.ID, &s.Start, &s.End); err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

// RawSite is a stored unfocused, playing site interval (latest revision).
type RawSite struct {
	ID     string
	Rev    int
	T0, T1 int64
	Body   []byte
}

// SiteRaw returns the stored raw site interval with this id.
func (t *Tx) SiteRaw(id string) (RawSite, bool, error) {
	var r RawSite
	var body string
	err := t.tx.QueryRow(`SELECT id, rev, t0, t1, body FROM site_raw WHERE id = ?`, id).Scan(&r.ID, &r.Rev, &r.T0, &r.T1, &body)
	if errors.Is(err, sql.ErrNoRows) {
		return r, false, nil
	}
	r.Body = []byte(body)
	return r, err == nil, err
}

// PutSiteRaw stores the latest revision of a raw site interval.
func (t *Tx) PutSiteRaw(r RawSite) error {
	_, err := t.tx.Exec(`
INSERT INTO site_raw (id, rev, t0, t1, body, updated_at) VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT(id) DO UPDATE SET rev = excluded.rev, t0 = excluded.t0, t1 = excluded.t1,
  body = excluded.body, updated_at = excluded.updated_at
WHERE excluded.rev > site_raw.rev`, r.ID, r.Rev, r.T0, r.T1, string(r.Body), t.now)
	return err
}

// SiteRawsOverlapping returns raw site intervals overlapping [t0, t1].
func (t *Tx) SiteRawsOverlapping(t0, t1 int64) ([]RawSite, error) {
	rows, err := t.tx.Query(`SELECT id, rev, t0, t1, body FROM site_raw WHERE t1 > ? AND t0 < ? ORDER BY t0`, t0, t1)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []RawSite
	for rows.Next() {
		var r RawSite
		var body string
		if err := rows.Scan(&r.ID, &r.Rev, &r.T0, &r.T1, &body); err != nil {
			return nil, err
		}
		r.Body = []byte(body)
		out = append(out, r)
	}
	return out, rows.Err()
}

// Piece is the Passive part of an unfocused site interval as last written.
type Piece struct {
	ID     string
	SiteID string
	SpanID string
	Rev    int
	T0, T1 int64
	Body   []byte // the event without rev, to detect changes
}

// PiecesForSite returns the pieces written for one raw site interval.
func (t *Tx) PiecesForSite(siteID string) ([]Piece, error) {
	rows, err := t.tx.Query(`SELECT id, site_id, span_id, rev, t0, t1, body FROM site_pieces WHERE site_id = ?`, siteID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Piece
	for rows.Next() {
		var p Piece
		var body string
		if err := rows.Scan(&p.ID, &p.SiteID, &p.SpanID, &p.Rev, &p.T0, &p.T1, &body); err != nil {
			return nil, err
		}
		p.Body = []byte(body)
		out = append(out, p)
	}
	return out, rows.Err()
}

// PutPiece stores a piece revision.
func (t *Tx) PutPiece(p Piece) error {
	_, err := t.tx.Exec(`
INSERT INTO site_pieces (id, site_id, span_id, rev, t0, t1, body, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(id) DO UPDATE SET rev = excluded.rev, t0 = excluded.t0, t1 = excluded.t1,
  body = excluded.body, updated_at = excluded.updated_at`,
		p.ID, p.SiteID, p.SpanID, p.Rev, p.T0, p.T1, string(p.Body), t.now)
	return err
}

// PutHeartbeat records an extension heartbeat.
func (t *Tx) PutHeartbeat(browser string, at int64, version string) error {
	_, err := t.tx.Exec(`
INSERT INTO heartbeats (browser, at, version, received_at) VALUES (?, ?, ?, ?)
ON CONFLICT(browser) DO UPDATE SET at = excluded.at, version = excluded.version, received_at = excluded.received_at`,
		browser, at, version, t.now)
	return err
}

// PutHeartbeat records an extension heartbeat.
func (q *Queue) PutHeartbeat(browser string, at int64, version string) error {
	return q.Tx(func(t *Tx) error { return t.PutHeartbeat(browser, at, version) })
}

// Heartbeat is the latest heartbeat of one browser's extension.
type Heartbeat struct {
	Browser    string
	At         int64
	Version    string
	ReceivedAt int64
}

// Heartbeats returns the latest heartbeat per browser.
func (q *Queue) Heartbeats() (map[string]Heartbeat, error) {
	rows, err := q.db.Query(`SELECT browser, at, version, received_at FROM heartbeats`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]Heartbeat{}
	for rows.Next() {
		var h Heartbeat
		if err := rows.Scan(&h.Browser, &h.At, &h.Version, &h.ReceivedAt); err != nil {
			return nil, err
		}
		out[h.Browser] = h
	}
	return out, rows.Err()
}

// SetMeta stores a key-value pair.
func (q *Queue) SetMeta(key, value string) error {
	return q.Tx(func(t *Tx) error { return t.SetMeta(key, value) })
}

// SetMeta stores a key-value pair.
func (t *Tx) SetMeta(key, value string) error {
	_, err := t.tx.Exec(`INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value`, key, value)
	return err
}

// Meta returns the value of key, or "" when unset.
func (q *Queue) Meta(key string) (string, error) {
	var v string
	err := q.db.QueryRow(`SELECT value FROM meta WHERE key = ?`, key).Scan(&v)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return v, err
}

// SetMetaJSON stores v as JSON.
func (q *Queue) SetMetaJSON(key string, v interface{}) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return q.SetMeta(key, string(data))
}

// MetaJSON reads a JSON value; ok is false when unset.
func (q *Queue) MetaJSON(key string, v interface{}) (bool, error) {
	s, err := q.Meta(key)
	if err != nil || s == "" {
		return false, err
	}
	return true, json.Unmarshal([]byte(s), v)
}

// Item is one event id and revision in a frozen batch.
type Item struct {
	ID  string
	Rev int
}

// Batch is a frozen batch: its sequence number and exact contents never
// change until a valid receipt arrives.
type Batch struct {
	InstallationID string
	Seq            int64
	Events         json.RawMessage // the JSON array, byte for byte the same on every send
	Items          []Item
	CreatedAt      int64
	Attempts       int
}

func seqKey(installationID string) string { return "next_seq:" + installationID }

// Frozen returns the frozen batch, if any.
func (q *Queue) Frozen() (*Batch, error) {
	var b *Batch
	err := q.Tx(func(t *Tx) error {
		var err error
		b, err = t.frozen()
		return err
	})
	return b, err
}

func (t *Tx) frozen() (*Batch, error) {
	var b Batch
	var evs string
	err := t.tx.QueryRow(`SELECT installation_id, seq, events, created_at, attempts FROM batches ORDER BY created_at, seq LIMIT 1`).
		Scan(&b.InstallationID, &b.Seq, &evs, &b.CreatedAt, &b.Attempts)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	b.Events = json.RawMessage(evs)
	rows, err := t.tx.Query(`SELECT id, rev FROM batch_items WHERE installation_id = ? AND seq = ? ORDER BY rowid`, b.InstallationID, b.Seq)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var it Item
		if err := rows.Scan(&it.ID, &it.Rev); err != nil {
			return nil, err
		}
		b.Items = append(b.Items, it)
	}
	return &b, rows.Err()
}

func (t *Tx) dropBatch(b *Batch) error {
	if _, err := t.tx.Exec(`DELETE FROM batch_items WHERE installation_id = ? AND seq = ?`, b.InstallationID, b.Seq); err != nil {
		return err
	}
	_, err := t.tx.Exec(`DELETE FROM batches WHERE installation_id = ? AND seq = ?`, b.InstallationID, b.Seq)
	return err
}

// Freeze returns the frozen batch for installationID, freezing a new one from
// the pending events when there is none. It returns nil when nothing is
// pending. A batch frozen for another installation (after a new pairing) is
// dropped; its events stay pending.
func (q *Queue) Freeze(installationID string, maxEvents, maxBytes int) (*Batch, error) {
	var out *Batch
	err := q.Tx(func(t *Tx) error {
		b, err := t.frozen()
		if err != nil {
			return err
		}
		if b != nil {
			if b.InstallationID == installationID {
				out = b
				return nil
			}
			if err := t.dropBatch(b); err != nil {
				return err
			}
		}
		rows, err := t.tx.Query(`SELECT id, rev, payload FROM events WHERE rev > acked_rev ORDER BY t0, id LIMIT ?`, maxEvents)
		if err != nil {
			return err
		}
		var buf bytes.Buffer
		buf.WriteByte('[')
		var items []Item
		for rows.Next() {
			var it Item
			var payload string
			if err := rows.Scan(&it.ID, &it.Rev, &payload); err != nil {
				rows.Close()
				return err
			}
			if len(items) > 0 && buf.Len()+len(payload)+2 > maxBytes {
				break
			}
			if len(items) > 0 {
				buf.WriteByte(',')
			}
			buf.WriteString(payload)
			items = append(items, it)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		if len(items) == 0 {
			return nil
		}
		buf.WriteByte(']')
		seq, err := t.nextSeq(installationID)
		if err != nil {
			return err
		}
		if _, err := t.tx.Exec(`INSERT INTO batches (installation_id, seq, events, created_at) VALUES (?, ?, ?, ?)`,
			installationID, seq, buf.String(), t.now); err != nil {
			return err
		}
		for _, it := range items {
			if _, err := t.tx.Exec(`INSERT INTO batch_items (installation_id, seq, id, rev) VALUES (?, ?, ?, ?)`,
				installationID, seq, it.ID, it.Rev); err != nil {
				return err
			}
		}
		out = &Batch{InstallationID: installationID, Seq: seq, Events: json.RawMessage(buf.String()), Items: items, CreatedAt: t.now}
		return nil
	})
	return out, err
}

// nextSeq consumes the next sequence number of an installation. Numbers
// start at 1 and are never reused, even when a batch is dropped.
func (t *Tx) nextSeq(installationID string) (int64, error) {
	var v string
	err := t.tx.QueryRow(`SELECT value FROM meta WHERE key = ?`, seqKey(installationID)).Scan(&v)
	seq := int64(1)
	if err == nil {
		seq, err = strconv.ParseInt(v, 10, 64)
		if err != nil {
			return 0, fmt.Errorf("corrupt sequence number: %w", err)
		}
	} else if !errors.Is(err, sql.ErrNoRows) {
		return 0, err
	}
	if err := t.SetMeta(seqKey(installationID), strconv.FormatInt(seq+1, 10)); err != nil {
		return 0, err
	}
	return seq, nil
}

// MarkAttempt counts one send of the frozen batch.
func (q *Queue) MarkAttempt(b *Batch) error {
	return q.Tx(func(t *Tx) error {
		_, err := t.tx.Exec(`UPDATE batches SET attempts = attempts + 1, last_attempt_at = ? WHERE installation_id = ? AND seq = ?`,
			t.now, b.InstallationID, b.Seq)
		return err
	})
}

// DropFrozen unfreezes a batch the server never logged (for example after
// 413); its events stay pending and its sequence number is not reused.
func (q *Queue) DropFrozen(b *Batch) error {
	return q.Tx(func(t *Tx) error { return t.dropBatch(b) })
}

// Result is one per-event status from a receipt.
type Result struct {
	ID     string `json:"id"`
	Status string `json:"status"`
	Reason string `json:"reason,omitempty"`
}

// ReceiptSummary counts what a receipt changed.
type ReceiptSummary struct {
	Accepted, Duplicate, Rejected, Ignored int
}

// ApplyReceipt applies a valid receipt for the frozen batch b. Only ids that
// were sent in b are acted on; anything else is ignored. accepted and
// duplicate mark the sent revision acknowledged; rejected moves it to the
// rejected table. The batch is then released.
func (q *Queue) ApplyReceipt(b *Batch, results []Result) (ReceiptSummary, error) {
	var sum ReceiptSummary
	sent := make(map[string]int, len(b.Items))
	for _, it := range b.Items {
		sent[it.ID] = it.Rev
	}
	payloads := map[string]json.RawMessage{}
	var arr []json.RawMessage
	if err := json.Unmarshal(b.Events, &arr); err == nil {
		for _, raw := range arr {
			var head struct {
				ID string `json:"id"`
			}
			if json.Unmarshal(raw, &head) == nil {
				payloads[head.ID] = raw
			}
		}
	}
	err := q.Tx(func(t *Tx) error {
		cur, err := t.frozen()
		if err != nil {
			return err
		}
		if cur == nil || cur.InstallationID != b.InstallationID || cur.Seq != b.Seq {
			return errors.New("queue: receipt for a batch that is not frozen")
		}
		done := map[string]bool{}
		for _, r := range results {
			rev, ok := sent[r.ID]
			if !ok || done[r.ID] {
				sum.Ignored++
				continue
			}
			done[r.ID] = true
			switch r.Status {
			case "accepted", "duplicate":
				if _, err := t.tx.Exec(`
UPDATE events SET
  acked_at = ?,
  pending_since = CASE WHEN rev > MAX(acked_rev, ?) THEN pending_since ELSE NULL END,
  acked_rev = MAX(acked_rev, ?)
WHERE id = ?`, t.now, rev, rev, r.ID); err != nil {
					return err
				}
				if r.Status == "accepted" {
					sum.Accepted++
				} else {
					sum.Duplicate++
				}
			case "rejected":
				var typ string
				var head struct {
					Type string `json:"type"`
				}
				if json.Unmarshal(payloads[r.ID], &head) == nil {
					typ = head.Type
				}
				if _, err := t.tx.Exec(`INSERT OR REPLACE INTO rejected (id, rev, type, payload, reason, seq, rejected_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
					r.ID, rev, typ, string(payloads[r.ID]), r.Reason, b.Seq, t.now); err != nil {
					return err
				}
				if _, err := t.tx.Exec(`DELETE FROM events WHERE id = ? AND rev = ?`, r.ID, rev); err != nil {
					return err
				}
				sum.Rejected++
			default:
				sum.Ignored++
			}
		}
		return t.dropBatch(b)
	})
	return sum, err
}

// Replay re-queues acknowledged events whose end is at or after since, so
// they go out again in new batches (the server answers "duplicate" for those
// it already has). It returns how many were re-queued.
func (q *Queue) Replay(since time.Time) (int, error) {
	var n int64
	err := q.Tx(func(t *Tx) error {
		res, err := t.tx.Exec(`UPDATE events SET acked_rev = -1, pending_since = ? WHERE acked_rev >= rev AND t1 >= ?`,
			t.now, since.UnixMilli())
		if err != nil {
			return err
		}
		n, err = res.RowsAffected()
		return err
	})
	return int(n), err
}

// Purge deletes acknowledged events older than 14 days and expired helper
// rows.
func (q *Queue) Purge() error {
	now := q.now()
	return q.Tx(func(t *Tx) error {
		stmts := []struct {
			sql string
			arg int64
		}{
			{`DELETE FROM events WHERE acked_rev >= rev AND acked_at < ?`, now.Add(-AckedRetention).UnixMilli()},
			{`DELETE FROM site_raw WHERE updated_at < ?`, now.Add(-RawSiteRetention).UnixMilli()},
			{`DELETE FROM site_pieces WHERE updated_at < ?`, now.Add(-RawSiteRetention).UnixMilli()},
			{`DELETE FROM rejected WHERE rejected_at < ?`, now.Add(-RejectedRetention).UnixMilli()},
		}
		for _, s := range stmts {
			if _, err := t.tx.Exec(s.sql, s.arg); err != nil {
				return err
			}
		}
		return nil
	})
}

// Stats summarises the queue for health records and `status`.
type Stats struct {
	Pending            int
	OldestPendingSince int64 // 0 when nothing is pending
	Acked              int
	Rejected           int
	Frozen             *Batch
}

// Stats returns queue counters.
func (q *Queue) Stats() (Stats, error) {
	var s Stats
	err := q.Tx(func(t *Tx) error {
		var oldest sql.NullInt64
		if err := t.tx.QueryRow(`SELECT COUNT(*), MIN(pending_since) FROM events WHERE rev > acked_rev`).Scan(&s.Pending, &oldest); err != nil {
			return err
		}
		s.OldestPendingSince = oldest.Int64
		if err := t.tx.QueryRow(`SELECT COUNT(*) FROM events WHERE acked_rev >= rev`).Scan(&s.Acked); err != nil {
			return err
		}
		if err := t.tx.QueryRow(`SELECT COUNT(*) FROM rejected`).Scan(&s.Rejected); err != nil {
			return err
		}
		var err error
		s.Frozen, err = t.frozen()
		return err
	})
	return s, err
}

// Get returns the stored revision of one event (tests and status).
func (q *Queue) Get(id string) (Event, bool, error) {
	var ev Event
	var tag sql.NullString
	var payload string
	err := q.db.QueryRow(`SELECT id, rev, type, tag, t0, t1, payload FROM events WHERE id = ?`, id).
		Scan(&ev.ID, &ev.Rev, &ev.Type, &tag, &ev.T0, &ev.T1, &payload)
	if errors.Is(err, sql.ErrNoRows) {
		return ev, false, nil
	}
	ev.Tag = tag.String
	ev.Payload = []byte(payload)
	return ev, err == nil, err
}

// AckedRev returns the highest acknowledged revision of an event (-1 when
// none) and whether the event is stored.
func (q *Queue) AckedRev(id string) (int, bool, error) {
	var r int
	err := q.db.QueryRow(`SELECT acked_rev FROM events WHERE id = ?`, id).Scan(&r)
	if errors.Is(err, sql.ErrNoRows) {
		return -1, false, nil
	}
	return r, err == nil, err
}

// All returns every stored event, ordered by start (tests and status).
func (q *Queue) All() ([]Event, error) {
	rows, err := q.db.Query(`SELECT id, rev, type, tag, t0, t1, payload FROM events ORDER BY t0, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Event
	for rows.Next() {
		var ev Event
		var tag sql.NullString
		var payload string
		if err := rows.Scan(&ev.ID, &ev.Rev, &ev.Type, &tag, &ev.T0, &ev.T1, &payload); err != nil {
			return nil, err
		}
		ev.Tag = tag.String
		ev.Payload = []byte(payload)
		out = append(out, ev)
	}
	return out, rows.Err()
}

// Rejected returns the ids in the local rejected table.
func (q *Queue) Rejected() ([]Result, error) {
	rows, err := q.db.Query(`SELECT id, COALESCE(reason, '') FROM rejected ORDER BY rejected_at`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Result
	for rows.Next() {
		var r Result
		if err := rows.Scan(&r.ID, &r.Reason); err != nil {
			return nil, err
		}
		r.Status = "rejected"
		out = append(out, r)
	}
	return out, rows.Err()
}
