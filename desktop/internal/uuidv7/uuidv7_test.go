package uuidv7_test

import (
	"strings"
	"testing"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/uuidv7"
)

func TestFormatVersionAndTime(t *testing.T) {
	ts := time.UnixMilli(1760090000123)
	id := uuidv7.New(ts)
	if !uuidv7.Valid(id) || id[14] != '7' || !strings.ContainsRune("89ab", rune(id[19])) {
		t.Fatalf("%s is not a UUIDv7", id)
	}
	if !strings.HasPrefix(strings.ReplaceAll(id, "-", ""), "0199cd8a0afb") {
		t.Fatalf("%s does not carry the timestamp", id)
	}
	if uuidv7.New(ts) == id {
		t.Fatal("two random ids are equal")
	}
}

func TestDerivedIsDeterministic(t *testing.T) {
	ts := time.UnixMilli(1760090000000)
	a := uuidv7.Derived(ts, "site", "x")
	if a != uuidv7.Derived(ts, "site", "x") || a == uuidv7.Derived(ts, "site", "y") || a == uuidv7.Derived(ts, "sitex") {
		t.Fatal("derived ids are not a function of their inputs")
	}
	if !uuidv7.Valid(a) || a[14] != '7' {
		t.Fatalf("%s is not a UUIDv7", a)
	}
	if uuidv7.Valid("not-a-uuid") || uuidv7.Valid(strings.Repeat("a", 36)) {
		t.Fatal("Valid accepts garbage")
	}
}
