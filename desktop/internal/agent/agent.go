// Package agent runs the desktop agent: the activity state machine fed by
// D-Bus, the queue, the extension socket, health records and uploads.
package agent

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/activity"
	"github.com/AndDev741/beConscient/desktop/internal/events"
	"github.com/AndDev741/beConscient/desktop/internal/health"
	"github.com/AndDev741/beConscient/desktop/internal/nativehost"
	"github.com/AndDev741/beConscient/desktop/internal/queue"
	"github.com/AndDev741/beConscient/desktop/internal/sites"
	"github.com/AndDev741/beConscient/desktop/internal/tz"
	"github.com/AndDev741/beConscient/desktop/internal/uuidv7"
	"github.com/AndDev741/beConscient/desktop/internal/version"
)

// Timing of the agent loop.
const (
	PollEvery       = 5 * time.Second
	CheckpointEvery = 5 * time.Minute
	PurgeEvery      = 6 * time.Hour
)

// MetaState and MetaHealth are the queue meta keys read by `status`.
const (
	MetaState  = "agent"
	MetaHealth = "health"
)

// StateRecord is the agent's last known state, for `status`.
type StateRecord struct {
	State     string `json:"state"`
	Since     int64  `json:"since"`
	UpdatedAt int64  `json:"updated_at"`
	Locked    bool   `json:"locked"`
	Inhibited bool   `json:"inhibited"`
	Playing   bool   `json:"playing"`
	Running   bool   `json:"running"`
}

// HealthRecord is the last health record's summary, for `status`.
type HealthRecord struct {
	At      int64                    `json:"at"`
	Sensors map[string]events.Sensor `json:"sensors"`
}

// Options configures an Agent.
type Options struct {
	Queue   *queue.Queue
	Source  activity.Source
	Delayer activity.SleepDelayer // optional
	Clock   activity.Clock
	TZ      tz.Func
	Log     *slog.Logger
	NewID   func(time.Time) string
	Config  activity.Config
	// SocketPath enables the extension socket when not empty.
	SocketPath string
	// Uploader runs in its own goroutine when set.
	Uploader interface{ Run(context.Context) }
}

type connMsg struct {
	conn   uint64
	msg    []byte
	closed bool
	reply  chan connReply
}

type connReply struct {
	data []byte
	err  error
}

// Agent is the running agent.
type Agent struct {
	o         Options
	mon       *activity.Monitor
	msgs      chan connMsg
	done      chan struct{}
	lastState activity.State
	saved     bool
}

// New builds an agent.
func New(o Options) *Agent {
	if o.Log == nil {
		o.Log = slog.Default()
	}
	if o.Clock == nil {
		o.Clock = activity.RealClock()
	}
	if o.TZ == nil {
		o.TZ = tz.Local
	}
	if o.NewID == nil {
		o.NewID = uuidv7.New
	}
	if o.Config == (activity.Config{}) {
		o.Config = activity.DefaultConfig()
	}
	a := &Agent{o: o, msgs: make(chan connMsg), done: make(chan struct{})}
	a.mon = activity.NewMonitor(o.Source, o.Clock, o.Config, o.NewID, a.writeIntervals, o.Log)
	return a
}

// Monitor exposes the activity monitor (tests and status).
func (a *Agent) Monitor() *activity.Monitor { return a.mon }

// Start reads the initial state, takes the sleep delay inhibitor and writes
// a first health record.
func (a *Agent) Start() {
	a.mon.Start()
	a.takeDelay()
	a.saveState(true)
	a.writeHealth(false)
}

// Run starts the agent and serves until ctx is done, then closes the open
// interval and writes a health record with expected_stop.
func (a *Agent) Run(ctx context.Context) error {
	defer close(a.done)
	// Bind the socket first: a second agent must exit before it measures.
	var srv *nativehost.Server
	if a.o.SocketPath != "" {
		var err error
		if srv, err = nativehost.Listen(a.o.SocketPath, a, a.o.Log); err != nil {
			return err
		}
	}
	a.Start()

	srvCtx, cancelSrv := context.WithCancel(context.Background())
	defer cancelSrv()
	srvDone := make(chan struct{})
	if srv != nil {
		go func() {
			defer close(srvDone)
			srv.Serve(srvCtx)
		}()
		a.o.Log.Info("listening for the browser extension", "socket", a.o.SocketPath)
	} else {
		close(srvDone)
	}
	upCtx, cancelUp := context.WithCancel(ctx)
	defer cancelUp()
	if a.o.Uploader != nil {
		go a.o.Uploader.Run(upCtx)
	}

	poll := time.NewTicker(PollEvery)
	defer poll.Stop()
	checkpoint := time.NewTicker(CheckpointEvery)
	defer checkpoint.Stop()
	purge := time.NewTicker(PurgeEvery)
	defer purge.Stop()
	a.purge()

	sigs := a.o.Source.Signals()
	for {
		select {
		case <-ctx.Done():
			a.Stop()
			cancelSrv()
			a.drainUntil(srvDone)
			return nil
		case <-poll.C:
			a.Poll()
		case <-checkpoint.C:
			a.Checkpoint()
		case <-purge.C:
			a.purge()
		case sig, ok := <-sigs:
			if !ok {
				a.o.Log.Error("D-Bus signal stream ended; lock and suspend changes are now seen only by polling")
				sigs = nil
				continue
			}
			a.HandleSignal(sig)
		case m := <-a.msgs:
			a.handle(m)
		}
	}
}

// drainUntil keeps answering socket handlers until the server has stopped,
// so none of them blocks on the loop.
func (a *Agent) drainUntil(done <-chan struct{}) {
	for {
		select {
		case <-done:
			return
		case m := <-a.msgs:
			if m.reply != nil {
				m.reply <- connReply{err: errors.New("agent stopping")}
			}
		}
	}
}

// Poll runs one 5-second poll.
func (a *Agent) Poll() {
	a.mon.Poll()
	a.saveState(false)
}

// Checkpoint writes the open interval and a health record (every 5 minutes).
func (a *Agent) Checkpoint() {
	a.mon.Checkpoint()
	a.saveState(true)
	a.writeHealth(false)
}

// HandleSignal reacts to a D-Bus signal. Before a suspend or shutdown it
// closes the open interval, writes a health record with expected_stop and
// then releases the delay inhibitor so the machine can go.
func (a *Agent) HandleSignal(sig activity.Signal) {
	switch sig.Kind {
	case activity.SigPrepareForSleep, activity.SigPrepareForShutdown:
		a.mon.Handle(sig)
		a.saveState(true)
		if sig.Value {
			a.o.Log.Info("machine is about to stop", "signal", sig.Kind.String())
			a.writeHealth(true)
			if a.o.Delayer != nil {
				a.o.Delayer.ReleaseSleepDelay()
			}
			return
		}
		a.o.Log.Info("resumed", "state", a.mon.State().String())
		a.takeDelay()
		a.writeHealth(false)
	default:
		a.mon.Handle(sig)
		a.saveState(false)
	}
}

// Stop closes the open interval and records an expected stop.
func (a *Agent) Stop() {
	a.mon.Stop()
	a.writeHealth(true)
	a.saveStopped()
	if a.o.Delayer != nil {
		a.o.Delayer.ReleaseSleepDelay()
	}
}

func (a *Agent) takeDelay() {
	if a.o.Delayer == nil {
		return
	}
	if err := a.o.Delayer.TakeSleepDelay(); err != nil {
		a.o.Log.Error("cannot take the logind delay inhibitor; the interval before a suspend may be cut at the last checkpoint", "err", err)
	}
}

func (a *Agent) purge() {
	if err := a.o.Queue.Purge(); err != nil {
		a.o.Log.Error("queue purge failed", "err", err)
	}
}

// writeIntervals is the monitor callback: every revision is written to the
// queue at once, and Passive intervals refresh the unfocused site parts.
func (a *Agent) writeIntervals(ivs []activity.Interval) {
	evs := make([]queue.Event, 0, len(ivs))
	for _, iv := range ivs {
		zone, off := a.o.TZ(iv.Start)
		ev := events.Activity{
			ID: iv.ID, Type: events.TypeActivity, Start: iv.Start.UnixMilli(), End: iv.End.UnixMilli(),
			Rev: iv.Rev, Reason: string(iv.Reason), TZ: zone, TZOffsetMin: off,
		}
		payload, err := json.Marshal(ev)
		if err != nil {
			a.o.Log.Error("encode interval", "err", err)
			continue
		}
		evs = append(evs, queue.Event{ID: ev.ID, Rev: ev.Rev, Type: ev.Type, Tag: ev.Reason, T0: ev.Start, T1: ev.End, Payload: payload})
	}
	if err := a.o.Queue.Put(evs...); err != nil {
		a.o.Log.Error("QUEUE WRITE FAILED: interval lost", "err", err)
		return
	}
	for _, iv := range ivs {
		if iv.Passive() {
			span := queue.Span{ID: iv.ID, Start: iv.Start.UnixMilli(), End: iv.End.UnixMilli()}
			if err := sites.Reconcile(a.o.Queue, span); err != nil {
				a.o.Log.Error("site reconcile failed", "err", err)
			}
		}
	}
}

func (a *Agent) openPassive() *queue.Span {
	iv, ok := a.mon.Open()
	if !ok || !iv.Passive() {
		return nil
	}
	return &queue.Span{ID: iv.ID, Start: iv.Start.UnixMilli(), End: iv.End.UnixMilli()}
}

// writeHealth writes one health record.
func (a *Agent) writeHealth(expectedStop bool) {
	now := a.o.Clock.Now()
	stats, err := a.o.Queue.Stats()
	if err != nil {
		a.o.Log.Error("queue stats", "err", err)
	}
	hbs, err := a.o.Queue.Heartbeats()
	if err != nil {
		a.o.Log.Error("heartbeats", "err", err)
	}
	zone, off := a.o.TZ(now)
	h := health.Build(health.Input{
		ID: a.o.NewID(now), Now: now, TZ: zone, TZOffsetMin: off, ClientVersion: version.Version,
		Activity: a.mon.Sensor(), Heartbeats: hbs, Stats: stats, ExpectedStop: expectedStop,
	})
	payload, err := json.Marshal(h)
	if err != nil {
		a.o.Log.Error("encode health", "err", err)
		return
	}
	if err := a.o.Queue.Put(queue.Event{ID: h.ID, Type: events.TypeHealth, T0: h.At, T1: h.At, Payload: payload}); err != nil {
		a.o.Log.Error("QUEUE WRITE FAILED: health record lost", "err", err)
		return
	}
	_ = a.o.Queue.SetMetaJSON(MetaHealth, HealthRecord{At: h.At, Sensors: h.Sensors})
}

func (a *Agent) saveState(force bool) {
	st := a.mon.State()
	if !force && a.saved && st == a.lastState {
		return
	}
	a.lastState, a.saved = st, true
	locked, inhibited, playing := a.mon.Flags()
	rec := StateRecord{State: st.String(), Since: a.mon.Since().UnixMilli(), UpdatedAt: a.o.Clock.Now().UnixMilli(),
		Locked: locked, Inhibited: inhibited, Playing: playing, Running: true}
	if err := a.o.Queue.SetMetaJSON(MetaState, rec); err != nil {
		a.o.Log.Error("save state", "err", err)
	}
}

func (a *Agent) saveStopped() {
	var rec StateRecord
	_, _ = a.o.Queue.MetaJSON(MetaState, &rec)
	rec.Running = false
	rec.UpdatedAt = a.o.Clock.Now().UnixMilli()
	_ = a.o.Queue.SetMetaJSON(MetaState, rec)
}

// HandleMessage implements nativehost.Handler. It runs on a socket
// goroutine and hands the message to the agent loop.
func (a *Agent) HandleMessage(conn uint64, msg []byte) ([]byte, error) {
	reply := make(chan connReply, 1)
	select {
	case a.msgs <- connMsg{conn: conn, msg: msg, reply: reply}:
	case <-a.done:
		return nil, errors.New("agent stopped")
	}
	select {
	case r := <-reply:
		return r.data, r.err
	case <-a.done:
		return nil, errors.New("agent stopped")
	}
}

// ConnClosed implements nativehost.Handler.
func (a *Agent) ConnClosed(conn uint64) {
	select {
	case a.msgs <- connMsg{conn: conn, closed: true}:
	case <-a.done:
	}
}

func (a *Agent) handle(m connMsg) {
	if m.closed {
		a.DropConnection(m.conn)
		return
	}
	data, err := a.HandleSync(m.conn, m.msg)
	if m.reply != nil {
		m.reply <- connReply{data: data, err: err}
	}
}

// DropConnection forgets a closed extension connection: a video it reported
// as playing no longer keeps the agent passive. Runs on the agent loop.
func (a *Agent) DropConnection(conn uint64) {
	a.mon.DropConn(conn)
	a.saveState(false)
}

// HandleSync processes one extension message on the agent loop and returns
// the reply frame.
func (a *Agent) HandleSync(conn uint64, msg []byte) ([]byte, error) {
	t, err := nativehost.ParseHead(msg)
	if err != nil {
		return nativehost.AckJSON(nil), nil
	}
	now := a.o.Clock.Now()
	switch t {
	case nativehost.TypeSite:
		ids, err := nativehost.StoreSite(a.o.Queue, msg, a.openPassive())
		if err != nil {
			a.o.Log.Error("site interval not stored", "err", err)
			return nativehost.AckJSON(nil), nil
		}
		return nativehost.AckJSON(ids), nil
	case nativehost.TypePlaying:
		var p nativehost.PlayingMsg
		if json.Unmarshal(msg, &p) == nil {
			a.mon.SetPlaying(conn, p.Playing)
			a.saveState(false)
		}
	case nativehost.TypeHeartbeat:
		if hb, ok := nativehost.ParseHeartbeat(msg, now); ok {
			if err := a.o.Queue.PutHeartbeat(hb.Browser, hb.At, hb.Version); err != nil {
				a.o.Log.Error("heartbeat not stored", "err", err)
			}
		}
	}
	return nativehost.AckJSON(nil), nil
}
