package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var box = strings.Repeat("ab", 32)

func newTestServer(t *testing.T) (*server, *httptest.Server) {
	s := &server{dir: t.TempDir()}
	ts := httptest.NewServer(s.routes())
	t.Cleanup(ts.Close)
	return s, ts
}

func put(t *testing.T, ts *httptest.Server, body string) string {
	resp, err := http.Post(ts.URL+"/v1/mailbox/"+box, "application/octet-stream", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("put: status %d", resp.StatusCode)
	}
	var out map[string]string
	json.NewDecoder(resp.Body).Decode(&out)
	return out["id"]
}

func list(t *testing.T, ts *httptest.Server, after string) (ids, bodies []string, more bool) {
	resp, err := http.Get(ts.URL + "/v1/mailbox/" + box + "?after=" + after)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out struct {
		Items []item
		More  bool
	}
	json.NewDecoder(resp.Body).Decode(&out)
	for _, it := range out.Items {
		b, _ := base64.StdEncoding.DecodeString(it.Data)
		ids, bodies = append(ids, it.ID), append(bodies, string(b))
	}
	return ids, bodies, out.More
}

func do(t *testing.T, method, url string) int {
	req, _ := http.NewRequest(method, url, nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return resp.StatusCode
}

func TestPutListAck(t *testing.T) {
	_, ts := newTestServer(t)
	a := put(t, ts, "first")
	b := put(t, ts, "second")

	ids, bodies, _ := list(t, ts, "")
	if len(ids) != 2 || bodies[0] != "first" || bodies[1] != "second" {
		t.Fatalf("got %v %v", ids, bodies)
	}
	if ids, _, _ := list(t, ts, a); len(ids) != 1 || ids[0] != b {
		t.Fatalf("after cursor: got %v", ids)
	}
	if code := do(t, http.MethodDelete, ts.URL+"/v1/mailbox/"+box+"?upto="+a); code != http.StatusNoContent {
		t.Fatalf("ack: status %d", code)
	}
	if ids, _, _ := list(t, ts, ""); len(ids) != 1 || ids[0] != b {
		t.Fatalf("after ack: got %v", ids)
	}
}

func TestEmptyMailboxListsNothing(t *testing.T) {
	_, ts := newTestServer(t)
	if ids, _, more := list(t, ts, ""); len(ids) != 0 || more {
		t.Fatalf("got %v", ids)
	}
}

func TestPaging(t *testing.T) {
	_, ts := newTestServer(t)
	for range pageSize + 3 {
		put(t, ts, "x")
	}
	ids, _, more := list(t, ts, "")
	if len(ids) != pageSize || !more {
		t.Fatalf("first page: %d items, more=%v", len(ids), more)
	}
	if rest, _, more := list(t, ts, ids[len(ids)-1]); len(rest) != 3 || more {
		t.Fatalf("second page: %d items, more=%v", len(rest), more)
	}
}

func TestRejectsBadInput(t *testing.T) {
	_, ts := newTestServer(t)
	cases := []struct {
		method, path string
		body         []byte
		want         int
	}{
		{http.MethodPost, "/v1/mailbox/not-hex", []byte("x"), http.StatusBadRequest},
		{http.MethodPost, "/v1/mailbox/" + box, nil, http.StatusBadRequest},
		{http.MethodPost, "/v1/mailbox/" + box, bytes.Repeat([]byte("x"), maxBlob+1), http.StatusRequestEntityTooLarge},
		{http.MethodGet, "/v1/mailbox/" + box + "?after=../../etc", nil, http.StatusBadRequest},
		{http.MethodDelete, "/v1/mailbox/" + box, nil, http.StatusBadRequest},
	}
	for _, c := range cases {
		req, _ := http.NewRequest(c.method, ts.URL+c.path, bytes.NewReader(c.body))
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != c.want {
			t.Errorf("%s %s: got %d, want %d", c.method, c.path[:min(len(c.path), 40)], resp.StatusCode, c.want)
		}
	}
}

func TestMailboxCap(t *testing.T) {
	s, ts := newTestServer(t)
	dir := filepath.Join(s.dir, box)
	os.MkdirAll(dir, 0o700)
	for i := range maxPerBox {
		os.WriteFile(filepath.Join(dir, fmt.Sprintf("%020d-%08x", i, i)), []byte("x"), 0o600)
	}
	resp, _ := http.Post(ts.URL+"/v1/mailbox/"+box, "", strings.NewReader("x"))
	resp.Body.Close()
	if resp.StatusCode != http.StatusInsufficientStorage {
		t.Fatalf("got %d, want 507", resp.StatusCode)
	}
}

func TestRejectsNewMailboxesWhenFull(t *testing.T) {
	s, ts := newTestServer(t)
	for i := range maxMailboxes {
		os.MkdirAll(filepath.Join(s.dir, fmt.Sprintf("%064x", i)), 0o700)
	}
	resp, _ := http.Post(ts.URL+"/v1/mailbox/"+box, "", strings.NewReader("x"))
	resp.Body.Close()
	if resp.StatusCode != http.StatusInsufficientStorage {
		t.Fatalf("new mailbox: got %d, want 507", resp.StatusCode)
	}
	// An existing mailbox still accepts uploads.
	os.MkdirAll(filepath.Join(s.dir, box), 0o700)
	put(t, ts, "still works")
}

func TestCleanupExpiresOldBlobs(t *testing.T) {
	s, ts := newTestServer(t)
	put(t, ts, "old")
	s.cleanup(time.Now().Add(retention + time.Hour))
	if _, err := os.Stat(filepath.Join(s.dir, box)); !os.IsNotExist(err) {
		t.Fatalf("expired mailbox should be removed, stat err = %v", err)
	}
}
