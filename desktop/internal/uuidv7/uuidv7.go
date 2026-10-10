// Package uuidv7 makes RFC 9562 version 7 UUIDs.
package uuidv7

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"time"
)

// New returns a random UUIDv7 whose timestamp is t.
func New(t time.Time) string {
	var b [16]byte
	if _, err := rand.Read(b[6:]); err != nil {
		panic(err) // crypto/rand never fails on Linux
	}
	return format(stamp(b, t))
}

// Derived returns a deterministic UUIDv7 whose timestamp is t and whose other
// bits come from a hash of parts. The same inputs always give the same id.
func Derived(t time.Time, parts ...string) string {
	h := sha256.New()
	h.Write([]byte("beconscient-uuid"))
	for _, p := range parts {
		h.Write([]byte{0})
		h.Write([]byte(p))
	}
	sum := h.Sum(nil)
	var b [16]byte
	copy(b[6:], sum[:10])
	return format(stamp(b, t))
}

func stamp(b [16]byte, t time.Time) [16]byte {
	ms := uint64(t.UnixMilli())
	b[0] = byte(ms >> 40)
	b[1] = byte(ms >> 32)
	b[2] = byte(ms >> 24)
	b[3] = byte(ms >> 16)
	b[4] = byte(ms >> 8)
	b[5] = byte(ms)
	b[6] = 0x70 | (b[6] & 0x0f)
	b[8] = 0x80 | (b[8] & 0x3f)
	return b
}

func format(b [16]byte) string {
	var out [36]byte
	hex.Encode(out[0:8], b[0:4])
	out[8] = '-'
	hex.Encode(out[9:13], b[4:6])
	out[13] = '-'
	hex.Encode(out[14:18], b[6:8])
	out[18] = '-'
	hex.Encode(out[19:23], b[8:10])
	out[23] = '-'
	hex.Encode(out[24:36], b[10:16])
	return string(out[:])
}

// Valid reports whether s is a canonical lowercase or uppercase UUID string of
// any version.
func Valid(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i := 0; i < 36; i++ {
		c := s[i]
		switch i {
		case 8, 13, 18, 23:
			if c != '-' {
				return false
			}
		default:
			if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F') {
				return false
			}
		}
	}
	return true
}
