package events_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"github.com/AndDev741/beConscient/desktop/internal/events"
	"github.com/AndDev741/beConscient/desktop/internal/testserver"
)

func TestValidFixturesHaveNoDisallowedFields(t *testing.T) {
	dir, err := testserver.ContractDir()
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(dir, "fixtures", "valid.json"))
	if err != nil {
		t.Fatal(err)
	}
	var evs []json.RawMessage
	if err := json.Unmarshal(data, &evs); err != nil {
		t.Fatal(err)
	}
	checked := 0
	for _, raw := range evs {
		var head struct{ Type string }
		json.Unmarshal(raw, &head)
		if _, ok := events.Allowed[head.Type]; !ok {
			continue // an Android type
		}
		checked++
		if bad, _ := events.Disallowed(raw); len(bad) > 0 {
			t.Fatalf("valid fixture flagged %v: %s", bad, raw)
		}
	}
	if checked < 3 {
		t.Fatalf("only %d desktop fixtures checked", checked)
	}
}

func TestForbiddenFixturesAreCaught(t *testing.T) {
	dir, _ := testserver.ContractDir()
	data, err := os.ReadFile(filepath.Join(dir, "fixtures", "forbidden.json"))
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Case   string          `json:"case"`
		Expect string          `json:"expect"`
		Fields []string        `json:"fields"`
		Event  json.RawMessage `json:"event"`
	}
	if err := json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	for _, c := range cases {
		if !strings.Contains(c.Case, "desktop") {
			continue
		}
		bad, _ := events.Disallowed(c.Event)
		sort.Strings(bad)
		want := append([]string(nil), c.Fields...)
		sort.Strings(want)
		if strings.Join(bad, ",") != strings.Join(want, ",") {
			t.Fatalf("%s: flagged %v, want %v", c.Case, bad, want)
		}
	}
}

func TestTruncate(t *testing.T) {
	if got := events.Truncate("héllo", 3); got != "hél" {
		t.Fatalf("%q", got)
	}
	if got := events.Truncate("a\xffb", 10); got != "a�b" {
		t.Fatalf("invalid UTF-8 kept: %q", got)
	}
}
