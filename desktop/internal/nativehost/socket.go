package nativehost

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/sys/unix"

	"github.com/AndDev741/beConscient/desktop/internal/paths"
)

// ErrAgentRunning means another agent already listens on the socket.
var ErrAgentRunning = errors.New("another agent is already listening on the socket")

// Handler processes messages arriving on the agent socket. Every message
// gets exactly one reply frame (an ack, possibly with no ids).
type Handler interface {
	HandleMessage(conn uint64, msg []byte) ([]byte, error)
	ConnClosed(conn uint64)
}

// Server is the agent's Unix socket: 0600 in a 0700 directory, peers checked
// with SO_PEERCRED, messages capped at MaxMessage.
type Server struct {
	path string
	ln   *net.UnixListener
	h    Handler
	log  *slog.Logger

	// AllowUID is the only peer uid accepted (the agent's own uid).
	AllowUID int
	// Limit is the largest accepted message.
	Limit int

	next atomic.Uint64
	wg   sync.WaitGroup
	mu   sync.Mutex
	open map[*net.UnixConn]bool
}

// Listen creates the socket. A stale socket file is removed; a live one
// means another agent is running.
func Listen(path string, h Handler, log *slog.Logger) (*Server, error) {
	if log == nil {
		log = slog.Default()
	}
	if err := paths.EnsurePrivateDir(filepath.Dir(path)); err != nil {
		return nil, err
	}
	if fi, err := os.Lstat(path); err == nil {
		if fi.Mode()&os.ModeSocket == 0 {
			return nil, fmt.Errorf("%s exists and is not a socket", path)
		}
		if c, err := net.DialTimeout("unix", path, time.Second); err == nil {
			c.Close()
			return nil, ErrAgentRunning
		}
		if err := os.Remove(path); err != nil {
			return nil, err
		}
	}
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, err
	}
	ln.SetUnlinkOnClose(true)
	if err := os.Chmod(path, paths.FileMode); err != nil {
		ln.Close()
		return nil, err
	}
	return &Server{path: path, ln: ln, h: h, log: log, AllowUID: os.Getuid(), Limit: MaxMessage,
		open: map[*net.UnixConn]bool{}}, nil
}

// Path returns the socket path.
func (s *Server) Path() string { return s.path }

// Serve accepts connections until ctx is done, then closes every connection
// and waits for the handlers.
func (s *Server) Serve(ctx context.Context) error {
	stop := context.AfterFunc(ctx, func() {
		s.ln.Close()
		s.mu.Lock()
		for c := range s.open {
			c.Close()
		}
		s.mu.Unlock()
	})
	defer stop()
	for {
		c, err := s.ln.AcceptUnix()
		if err != nil {
			if ctx.Err() != nil {
				s.wg.Wait()
				return nil
			}
			if errors.Is(err, net.ErrClosed) {
				s.wg.Wait()
				return err
			}
			s.log.Warn("socket accept failed", "err", err)
			time.Sleep(100 * time.Millisecond)
			continue
		}
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			s.serveConn(c)
		}()
	}
}

// Close stops listening.
func (s *Server) Close() error { return s.ln.Close() }

func (s *Server) serveConn(c *net.UnixConn) {
	defer c.Close()
	uid, err := PeerUID(c)
	if err != nil {
		s.log.Warn("socket: cannot read peer credentials; connection refused", "err", err)
		return
	}
	if int(uid) != s.AllowUID {
		s.log.Warn("socket: connection from another user refused", "uid", uid)
		return
	}
	s.mu.Lock()
	s.open[c] = true
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		delete(s.open, c)
		s.mu.Unlock()
	}()
	id := s.next.Add(1)
	defer s.h.ConnClosed(id)
	for {
		msg, err := ReadFrame(c, s.Limit)
		if err != nil {
			var tl *TooLargeError
			if errors.As(err, &tl) {
				s.log.Warn("socket: oversize message refused; closing the connection", "size", tl.Size)
			} else if !errors.Is(err, io.EOF) && !errors.Is(err, net.ErrClosed) {
				s.log.Debug("socket read ended", "err", err)
			}
			return
		}
		reply, err := s.h.HandleMessage(id, msg)
		if err != nil {
			s.log.Error("socket: message not handled", "err", err)
			return
		}
		if reply == nil {
			reply = AckJSON(nil)
		}
		c.SetWriteDeadline(time.Now().Add(10 * time.Second))
		if err := WriteFrame(c, reply); err != nil {
			return
		}
	}
}

// PeerUID returns the uid of the process at the other end of c.
func PeerUID(c *net.UnixConn) (uint32, error) {
	raw, err := c.SyscallConn()
	if err != nil {
		return 0, err
	}
	var cred *unix.Ucred
	var cerr error
	if err := raw.Control(func(fd uintptr) {
		cred, cerr = unix.GetsockoptUcred(int(fd), unix.SOL_SOCKET, unix.SO_PEERCRED)
	}); err != nil {
		return 0, err
	}
	if cerr != nil {
		return 0, cerr
	}
	return cred.Uid, nil
}

// Client is the host's connection to the agent.
type Client struct {
	c     *net.UnixConn
	limit int
}

// Dial connects to the agent socket and checks that the agent runs as the
// same user.
func Dial(path string) (*Client, error) {
	conn, err := net.DialTimeout("unix", path, 2*time.Second)
	if err != nil {
		return nil, err
	}
	uc := conn.(*net.UnixConn)
	uid, err := PeerUID(uc)
	if err != nil || int(uid) != os.Getuid() {
		uc.Close()
		return nil, fmt.Errorf("agent socket peer is not the current user")
	}
	return &Client{c: uc, limit: MaxMessage}, nil
}

// Roundtrip sends one message and waits for the agent's reply.
func (c *Client) Roundtrip(msg []byte) ([]byte, error) {
	c.c.SetDeadline(time.Now().Add(15 * time.Second))
	if err := WriteFrame(c.c, msg); err != nil {
		return nil, err
	}
	return ReadFrame(c.c, c.limit)
}

// Close closes the connection.
func (c *Client) Close() error { return c.c.Close() }
