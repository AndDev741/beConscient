// Package nativehost implements the native messaging host (the process the
// browser starts for each extension connection) and the agent's Unix socket
// that the host forwards to (contract/native-messaging.md).
package nativehost

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"strings"
)

// MaxMessage is the largest message accepted from the extension and over the
// agent socket.
const MaxMessage = 64 << 10

// maxDiscard is the largest refused message whose body is skipped to keep
// the stream in sync; anything larger ends the connection.
const maxDiscard = 1 << 20

// ExtensionID is fixed by the public key in the extension manifest.
const ExtensionID = "hhddmdmnpejagnnmenjdgdifpimneeok"

// Origin is the only origin allowed to start the host.
const Origin = "chrome-extension://" + ExtensionID + "/"

// HostName is the native messaging host name.
const HostName = "org.beconscient.agent"

// TooLargeError reports a frame above the limit.
type TooLargeError struct{ Size uint32 }

func (e *TooLargeError) Error() string {
	return fmt.Sprintf("message of %d bytes refused (limit %d)", e.Size, MaxMessage)
}

// IsHostInvocation reports whether the binary was started by the browser as
// a native messaging host.
func IsHostInvocation(args []string) bool {
	return len(args) > 0 && strings.HasPrefix(args[0], "chrome-extension://")
}

// CheckOrigin accepts only the pinned extension origin.
func CheckOrigin(origin string) error {
	if origin != Origin {
		return fmt.Errorf("refusing native messaging origin %q", origin)
	}
	return nil
}

// ReadFrame reads one length-prefixed (32-bit little-endian) message. A
// message above limit returns *TooLargeError without reading its body.
func ReadFrame(r io.Reader, limit int) ([]byte, error) {
	var hdr [4]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, err
	}
	n := binary.LittleEndian.Uint32(hdr[:])
	if n > uint32(limit) {
		return nil, &TooLargeError{Size: n}
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		if errors.Is(err, io.EOF) {
			err = io.ErrUnexpectedEOF
		}
		return nil, err
	}
	return buf, nil
}

// skip discards the body of a refused frame when it is small enough to keep
// the stream usable.
func skip(r io.Reader, e *TooLargeError) error {
	if e.Size > maxDiscard {
		return e
	}
	_, err := io.CopyN(io.Discard, r, int64(e.Size))
	return err
}

// WriteFrame writes one length-prefixed message.
func WriteFrame(w io.Writer, msg []byte) error {
	if len(msg) > MaxMessage {
		return &TooLargeError{Size: uint32(len(msg))}
	}
	buf := make([]byte, 4+len(msg))
	binary.LittleEndian.PutUint32(buf, uint32(len(msg)))
	copy(buf[4:], msg)
	_, err := w.Write(buf)
	return err
}
