package nativehost_test

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/nativehost"
	"github.com/AndDev741/beConscient/desktop/internal/queue"
)

const siteMsg = `{"t":"site","id":"0192f3a1-0000-7000-8000-00000000000b","rev":2,"site":"youtube","browser":"chrome",
 "start":1760090000000,"end":1760091200000,"playing":true,"focused":true,"title":"A video title","page_kind":null,
 "tz":"Europe/Lisbon","tz_offset_min":60}`

func frame(msg string) []byte {
	var b bytes.Buffer
	nativehost.WriteFrame(&b, []byte(msg))
	return b.Bytes()
}

func readAcks(t *testing.T, out *bytes.Buffer) []nativehost.Ack {
	t.Helper()
	var acks []nativehost.Ack
	for out.Len() > 0 {
		msg, err := nativehost.ReadFrame(out, nativehost.MaxMessage)
		if err != nil {
			t.Fatal(err)
		}
		var a nativehost.Ack
		if err := json.Unmarshal(msg, &a); err != nil {
			t.Fatal(err)
		}
		acks = append(acks, a)
	}
	return acks
}

// shortDir returns a short temporary directory (socket paths are limited to
// 108 bytes).
func shortDir(t *testing.T) string {
	t.Helper()
	d, err := os.MkdirTemp("", "bc")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(d) })
	return d
}

func TestCheckOrigin(t *testing.T) {
	if err := nativehost.CheckOrigin("chrome-extension://hhddmdmnpejagnnmenjdgdifpimneeok/"); err != nil {
		t.Fatal(err)
	}
	for _, bad := range []string{
		"chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/",
		"chrome-extension://hhddmdmnpejagnnmenjdgdifpimneeok",
		"chrome-extension://hhddmdmnpejagnnmenjdgdifpimneeok/evil",
		"chrome-extension://hhddmdmnpejagnnmenjdgdifpimneeok.evil/",
		"CHROME-EXTENSION://hhddmdmnpejagnnmenjdgdifpimneeok/",
		"",
	} {
		if err := nativehost.CheckOrigin(bad); err == nil {
			t.Fatalf("origin %q accepted", bad)
		}
	}
	if !nativehost.IsHostInvocation([]string{"chrome-extension://x/"}) || nativehost.IsHostInvocation([]string{"run"}) ||
		nativehost.IsHostInvocation(nil) {
		t.Fatal("host mode detection")
	}
}

func TestReadFrameRefusesOversize(t *testing.T) {
	var b bytes.Buffer
	binary.Write(&b, binary.LittleEndian, uint32(nativehost.MaxMessage+1))
	_, err := nativehost.ReadFrame(&b, nativehost.MaxMessage)
	var tl *nativehost.TooLargeError
	if !errors.As(err, &tl) {
		t.Fatalf("got %v", err)
	}
	if err := nativehost.WriteFrame(io.Discard, make([]byte, nativehost.MaxMessage+1)); err == nil {
		t.Fatal("oversize write accepted")
	}
}

func TestHostWithAgentDownWritesToQueue(t *testing.T) {
	dir := shortDir(t)
	qpath := filepath.Join(dir, "state", "queue.db")
	in := bytes.NewBuffer(nil)
	in.Write(frame(siteMsg))
	in.Write(frame(`{"t":"heartbeat","browser":"brave","version":"0.1.0","at":1760090000000}`))
	in.Write(frame(`{"t":"playing","playing":true,"at":1760090000000}`))
	var out bytes.Buffer
	h := &nativehost.Host{In: in, Out: &out, SocketPath: filepath.Join(dir, "run", "agent.sock"),
		OpenQueue: func() (*queue.Queue, error) { return queue.Open(qpath) }}
	if err := h.Run(); err != nil {
		t.Fatal(err)
	}
	acks := readAcks(t, &out)
	if len(acks) != 1 || len(acks[0].IDs) != 1 || acks[0].IDs[0] != "0192f3a1-0000-7000-8000-00000000000b" {
		t.Fatalf("acks %+v", acks)
	}
	q, err := queue.Open(qpath)
	if err != nil {
		t.Fatal(err)
	}
	defer q.Close()
	ev, ok, _ := q.Get("0192f3a1-0000-7000-8000-00000000000b")
	if !ok || ev.Type != "browser.site" || ev.Rev != 2 {
		t.Fatalf("queued %+v %v", ev, ok)
	}
	hbs, _ := q.Heartbeats()
	if hbs["brave"].Version != "0.1.0" {
		t.Fatalf("heartbeat %+v", hbs)
	}
}

func TestHostSkipsOversizeMessageAndContinues(t *testing.T) {
	dir := shortDir(t)
	in := bytes.NewBuffer(nil)
	binary.Write(in, binary.LittleEndian, uint32(nativehost.MaxMessage+10))
	in.Write(make([]byte, nativehost.MaxMessage+10))
	in.Write(frame(siteMsg))
	var out bytes.Buffer
	h := &nativehost.Host{In: in, Out: &out, SocketPath: filepath.Join(dir, "none.sock"),
		OpenQueue: func() (*queue.Queue, error) { return queue.Open(filepath.Join(dir, "q.db")) }}
	if err := h.Run(); err != nil {
		t.Fatal(err)
	}
	if acks := readAcks(t, &out); len(acks) != 1 {
		t.Fatalf("acks %+v", acks)
	}
	// A huge length ends the host instead of reading gigabytes.
	in2 := bytes.NewBuffer(nil)
	binary.Write(in2, binary.LittleEndian, uint32(1<<30))
	h2 := &nativehost.Host{In: in2, Out: io.Discard, SocketPath: filepath.Join(dir, "none.sock"),
		OpenQueue: func() (*queue.Queue, error) { return nil, errors.New("unused") }}
	if err := h2.Run(); err == nil {
		t.Fatal("a 1 GiB frame must stop the host")
	}
}

type recorder struct {
	mu     sync.Mutex
	msgs   [][]byte
	closed int
}

func (r *recorder) HandleMessage(conn uint64, msg []byte) ([]byte, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.msgs = append(r.msgs, msg)
	t, _ := nativehost.ParseHead(msg)
	if t == nativehost.TypeSite {
		var m struct {
			ID string `json:"id"`
		}
		json.Unmarshal(msg, &m)
		return nativehost.AckJSON([]string{m.ID}), nil
	}
	return nativehost.AckJSON(nil), nil
}

func (r *recorder) ConnClosed(uint64) {
	r.mu.Lock()
	r.closed++
	r.mu.Unlock()
}

func (r *recorder) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.msgs)
}

func serve(t *testing.T, path string, h nativehost.Handler, tweak func(*nativehost.Server)) (*nativehost.Server, func()) {
	t.Helper()
	srv, err := nativehost.Listen(path, h, nil)
	if err != nil {
		t.Fatal(err)
	}
	if tweak != nil {
		tweak(srv)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		srv.Serve(ctx)
		close(done)
	}()
	return srv, func() {
		cancel()
		<-done
	}
}

func TestHostForwardsToRunningAgent(t *testing.T) {
	dir := shortDir(t)
	sock := filepath.Join(dir, "run", "agent.sock")
	rec := &recorder{}
	_, stop := serve(t, sock, rec, nil)
	defer stop()
	in := bytes.NewBuffer(nil)
	in.Write(frame(siteMsg))
	in.Write(frame(`{"t":"playing","playing":true,"at":1}`))
	var out bytes.Buffer
	h := &nativehost.Host{In: in, Out: &out, SocketPath: sock,
		OpenQueue: func() (*queue.Queue, error) { return nil, errors.New("must not write directly") }}
	if err := h.Run(); err != nil {
		t.Fatal(err)
	}
	if rec.count() != 2 {
		t.Fatalf("agent got %d messages", rec.count())
	}
	if acks := readAcks(t, &out); len(acks) != 1 || acks[0].IDs[0] != "0192f3a1-0000-7000-8000-00000000000b" {
		t.Fatalf("acks %+v", acks)
	}
}

func TestSocketPermissions(t *testing.T) {
	dir := shortDir(t)
	sock := filepath.Join(dir, "beconscient", "agent.sock")
	_, stop := serve(t, sock, &recorder{}, nil)
	defer stop()
	fi, err := os.Lstat(sock)
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode()&os.ModeSocket == 0 || fi.Mode().Perm() != 0o600 {
		t.Fatalf("socket mode %v, want 0600 socket", fi.Mode())
	}
	di, _ := os.Stat(filepath.Dir(sock))
	if di.Mode().Perm() != 0o700 {
		t.Fatalf("socket dir mode %o, want 700", di.Mode().Perm())
	}
	// A second agent refuses to start; it does not steal the socket.
	if _, err := nativehost.Listen(sock, &recorder{}, nil); !errors.Is(err, nativehost.ErrAgentRunning) {
		t.Fatalf("second listen: %v", err)
	}
}

func TestSocketRefusesAnotherUID(t *testing.T) {
	dir := shortDir(t)
	sock := filepath.Join(dir, "agent.sock")
	rec := &recorder{}
	_, stop := serve(t, sock, rec, func(s *nativehost.Server) { s.AllowUID = os.Getuid() + 1 })
	defer stop()
	c, err := nativehost.Dial(sock)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if _, err := c.Roundtrip([]byte(siteMsg)); err == nil {
		t.Fatal("a peer with another uid got an answer")
	}
	if rec.count() != 0 {
		t.Fatal("a message from another uid reached the agent")
	}
}

func TestSocketRefusesOversizeMessages(t *testing.T) {
	dir := shortDir(t)
	sock := filepath.Join(dir, "agent.sock")
	rec := &recorder{}
	_, stop := serve(t, sock, rec, nil)
	defer stop()
	c, err := nativehost.Dial(sock)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	big := bytes.Repeat([]byte("a"), nativehost.MaxMessage) // fits the frame, then one more byte of JSON
	msg := append([]byte(`{"t":"site","x":"`), big...)
	if _, err := c.Roundtrip(msg); err == nil {
		t.Fatal("oversize message answered")
	}
	time.Sleep(50 * time.Millisecond)
	if rec.count() != 0 {
		t.Fatal("an oversize message reached the agent")
	}
	// A normal message on a new connection still works.
	c2, err := nativehost.Dial(sock)
	if err != nil {
		t.Fatal(err)
	}
	defer c2.Close()
	if _, err := c2.Roundtrip([]byte(siteMsg)); err != nil {
		t.Fatal(err)
	}
}

func TestStaleSocketIsReplaced(t *testing.T) {
	dir := shortDir(t)
	sock := filepath.Join(dir, "agent.sock")
	// A crashed agent leaves its socket file behind.
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: sock, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	ln.SetUnlinkOnClose(false)
	ln.Close()
	if _, err := os.Lstat(sock); err != nil {
		t.Fatal("stale socket not left behind")
	}
	rec := &recorder{}
	_, stop := serve(t, sock, rec, nil)
	defer stop()
	c, err := nativehost.Dial(sock)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if _, err := c.Roundtrip([]byte(siteMsg)); err != nil || rec.count() != 1 {
		t.Fatalf("new agent not reachable: %v", err)
	}
}

func TestHostFallsBackWhenAgentGoesAway(t *testing.T) {
	dir := shortDir(t)
	sock := filepath.Join(dir, "agent.sock")
	rec := &recorder{}
	_, stop := serve(t, sock, rec, nil)
	pr, pw := io.Pipe()
	var out safeBuffer
	qpath := filepath.Join(dir, "q.db")
	h := &nativehost.Host{In: pr, Out: &out, SocketPath: sock, RedialAfter: time.Hour,
		OpenQueue: func() (*queue.Queue, error) { return queue.Open(qpath) }}
	done := make(chan error)
	go func() { done <- h.Run() }()
	pw.Write(frame(siteMsg))
	waitFor(t, func() bool { return rec.count() == 1 })
	stop() // the agent stops
	second := `{"t":"site","id":"0192f3a1-0000-7000-8000-00000000000c","rev":1,"site":"youtube","browser":"chrome","start":1,"end":2,"playing":false,"focused":true}`
	pw.Write(frame(second))
	pw.Close()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	q, _ := queue.Open(qpath)
	defer q.Close()
	if _, ok, _ := q.Get("0192f3a1-0000-7000-8000-00000000000c"); !ok {
		t.Fatal("message after the agent stopped was not written directly")
	}
}

type safeBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *safeBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	for i := 0; i < 200; i++ {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("condition not reached")
}

func TestHostReplaysPlayingToARestartedAgent(t *testing.T) {
	dir := shortDir(t)
	sock := filepath.Join(dir, "agent.sock")
	pr, pw := io.Pipe()
	var out safeBuffer
	h := &nativehost.Host{In: pr, Out: &out, SocketPath: sock, RedialAfter: time.Nanosecond,
		OpenQueue: func() (*queue.Queue, error) { return queue.Open(filepath.Join(dir, "q.db")) }}
	done := make(chan error)
	go func() { done <- h.Run() }()
	pw.Write(frame(`{"t":"playing","playing":true,"at":1}`)) // agent down: dropped
	pw.Write(frame(siteMsg))                                 // agent down: written directly
	waitFor(t, func() bool { out.mu.Lock(); defer out.mu.Unlock(); return out.b.Len() > 0 })
	rec := &recorder{}
	_, stop := serve(t, sock, rec, nil)
	defer stop()
	pw.Write(frame(strings.Replace(siteMsg, `"rev":2`, `"rev":3`, 1))) // reconnects, replays playing, forwards
	pw.Close()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	rec.mu.Lock()
	defer rec.mu.Unlock()
	if len(rec.msgs) != 2 {
		t.Fatalf("agent got %d messages", len(rec.msgs))
	}
	if typ, _ := nativehost.ParseHead(rec.msgs[0]); typ != nativehost.TypePlaying {
		t.Fatalf("first message %s, want the replayed playing state", rec.msgs[0])
	}
}
