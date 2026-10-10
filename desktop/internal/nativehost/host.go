package nativehost

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/queue"
)

// Host is the native messaging host: it reads framed messages from the
// browser on In, forwards them to the running agent over its socket, and
// writes them straight to the queue when the agent is not running. Acks go
// back to the extension on Out only after a durable write.
type Host struct {
	In         io.Reader
	Out        io.Writer
	Log        *slog.Logger
	SocketPath string
	// OpenQueue opens the queue for direct writes (called lazily).
	OpenQueue func() (*queue.Queue, error)
	Now       func() time.Time
	// RedialAfter is how long the host waits before trying the agent again
	// after a failed connection.
	RedialAfter time.Duration

	client      *Client
	lastDial    time.Time
	q           *queue.Queue
	lastPlaying []byte
}

// Run serves until the browser closes stdin.
func (h *Host) Run() error {
	if h.Log == nil {
		h.Log = slog.Default()
	}
	if h.Now == nil {
		h.Now = time.Now
	}
	if h.RedialAfter == 0 {
		h.RedialAfter = 30 * time.Second
	}
	defer func() {
		if h.client != nil {
			h.client.Close()
		}
		if h.q != nil {
			h.q.Close()
		}
	}()
	h.dial(false)
	for {
		msg, err := ReadFrame(h.In, MaxMessage)
		if err != nil {
			var tl *TooLargeError
			if errors.As(err, &tl) {
				h.Log.Warn("native host: oversize message refused", "size", tl.Size)
				if err := skip(h.In, tl); err != nil {
					return err
				}
				continue
			}
			if errors.Is(err, io.EOF) {
				return nil
			}
			return err
		}
		if !json.Valid(msg) {
			h.Log.Warn("native host: message is not JSON; ignored")
			continue
		}
		ids, err := h.handle(msg)
		if err != nil {
			// No ack: the extension keeps its copy and resends later.
			h.Log.Error("native host: message not stored", "err", err)
			continue
		}
		if len(ids) > 0 {
			if err := WriteFrame(h.Out, AckJSON(ids)); err != nil {
				return err
			}
		}
	}
}

func (h *Host) dial(replayPlaying bool) {
	h.lastDial = h.Now()
	c, err := Dial(h.SocketPath)
	if err != nil {
		h.Log.Info("native host: agent not reachable; writing to the queue directly", "err", err)
		return
	}
	// A restarted agent does not know a video is still playing: tell it.
	if replayPlaying && h.lastPlaying != nil {
		if _, err := c.Roundtrip(h.lastPlaying); err != nil {
			c.Close()
			return
		}
	}
	h.client = c
}

func (h *Host) handle(msg []byte) ([]string, error) {
	t, _ := ParseHead(msg)
	if t == TypePlaying {
		h.lastPlaying = append([]byte(nil), msg...)
	}
	if h.client == nil && h.Now().Sub(h.lastDial) >= h.RedialAfter {
		h.dial(t != TypePlaying)
	}
	if h.client != nil {
		reply, err := h.client.Roundtrip(msg)
		if err == nil {
			var ack Ack
			if json.Unmarshal(reply, &ack) == nil && ack.T == TypeAck {
				return ack.IDs, nil
			}
			return nil, errors.New("agent sent an invalid reply")
		}
		h.Log.Warn("native host: agent connection lost; writing to the queue directly", "err", err)
		h.client.Close()
		h.client = nil
	}
	if h.q == nil {
		q, err := h.OpenQueue()
		if err != nil {
			return nil, err
		}
		h.q = q
	}
	return HandleDirect(h.q, msg, h.Now())
}
