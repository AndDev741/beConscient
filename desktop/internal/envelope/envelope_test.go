package envelope_test

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/AndDev741/beConscient/desktop/internal/envelope"
	"github.com/AndDev741/beConscient/desktop/internal/testserver"
)

type vector struct {
	Producer          string           `json:"producer"`
	Kid               string           `json:"kid"`
	Purpose           string           `json:"purpose"`
	Info              string           `json:"info"`
	Plaintext         string           `json:"plaintext"`
	Request           envelope.Request `json:"request"`
	ResponseKey       string           `json:"response_key"`
	ResponsePlaintext string           `json:"response_plaintext"`
	Response          json.RawMessage  `json:"response"`
}

func readVectors(t *testing.T, path string) []vector {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var vs []vector
	if err := json.Unmarshal(data, &vs); err != nil {
		t.Fatalf("%s: %v", path, err)
	}
	return vs
}

// checkVector opens the request as the server would, compares the exported
// response key, and opens the response with the agent's own code.
func checkVector(t *testing.T, v vector) {
	t.Helper()
	key, sk, err := testserver.LoadTestKey()
	if err != nil {
		t.Fatal(err)
	}
	if v.Info != "beconscient/v1/"+v.Purpose {
		t.Fatalf("info %q does not match purpose %q", v.Info, v.Purpose)
	}
	body, _ := json.Marshal(v.Request)
	pt, rk, err := testserver.OpenRequest(sk, key.Kid, v.Info, body)
	if err != nil {
		t.Fatalf("open request: %v", err)
	}
	if string(pt) != v.Plaintext {
		t.Fatal("plaintext mismatch")
	}
	if envelope.B64.EncodeToString(rk) != v.ResponseKey {
		t.Fatal("response key mismatch")
	}
	resp, err := envelope.OpenResponse(rk, v.Response)
	if err != nil {
		t.Fatalf("open response: %v", err)
	}
	if string(resp) != v.ResponsePlaintext {
		t.Fatal("response plaintext mismatch")
	}
}

func TestReferenceVectors(t *testing.T) {
	dir, err := testserver.ContractDir()
	if err != nil {
		t.Fatal(err)
	}
	vs := readVectors(t, filepath.Join(dir, "vectors", "go-reference.json"))
	if len(vs) < 2 {
		t.Fatalf("go-reference.json has %d vectors", len(vs))
	}
	for _, v := range vs {
		t.Run(v.Purpose, func(t *testing.T) { checkVector(t, v) })
	}
}

// Every implementation verifies every vector file (contract/README.md).
func TestAllVectorFiles(t *testing.T) {
	dir, err := testserver.ContractDir()
	if err != nil {
		t.Fatal(err)
	}
	files, _ := filepath.Glob(filepath.Join(dir, "vectors", "*.json"))
	if len(files) == 0 {
		t.Fatal("no vector files")
	}
	for _, f := range files {
		for _, v := range readVectors(t, f) {
			t.Run(filepath.Base(f)+"/"+v.Producer+"/"+v.Purpose, func(t *testing.T) { checkVector(t, v) })
		}
	}
}

func TestSealOpensOnServerAndResponseOpensHere(t *testing.T) {
	key, sk, err := testserver.LoadTestKey()
	if err != nil {
		t.Fatal(err)
	}
	k, err := envelope.NewKeyB64(key.Kid, key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	for _, info := range []string{envelope.InfoBatch, envelope.InfoPair} {
		sealed, err := envelope.Seal(k, info, []byte(`{"x":1}`))
		if err != nil {
			t.Fatal(err)
		}
		body, _ := sealed.Body()
		pt, rk, err := testserver.OpenRequest(sk, key.Kid, info, body)
		if err != nil || string(pt) != `{"x":1}` {
			t.Fatalf("server could not open %s: %v", info, err)
		}
		other := envelope.InfoPair
		if info == envelope.InfoPair {
			other = envelope.InfoBatch
		}
		if _, _, err := testserver.OpenRequest(sk, key.Kid, other, body); err == nil {
			t.Fatalf("an envelope sealed for %s opened as %s", info, other)
		}
		resp, _ := testserver.SealResponse(rk, []byte(`{"ok":true}`))
		got, err := sealed.Open(resp)
		if err != nil || string(got) != `{"ok":true}` {
			t.Fatalf("response did not open: %v", err)
		}
		// A response made for another request (a replay) does not open.
		other2, _ := envelope.Seal(k, info, []byte(`{}`))
		if _, err := other2.Open(resp); err == nil {
			t.Fatal("a response opened under another request's key")
		}
		// A tampered response does not open.
		var r envelope.Response
		json.Unmarshal(resp, &r)
		r.Ct = r.Ct[:len(r.Ct)-2] + "AA"
		bad, _ := json.Marshal(r)
		if _, err := sealed.Open(bad); err == nil {
			t.Fatal("a tampered response opened")
		}
		// A captive portal's 200 does not open.
		if _, err := sealed.Open([]byte(`<html>login</html>`)); err == nil {
			t.Fatal("html opened as a response")
		}
	}
}
