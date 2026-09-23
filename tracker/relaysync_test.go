package main

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// Fixed vector shared with the Android app's RelayCrypto; both must produce these exact values.
// Cross-checked against an independent HMAC-based HKDF implementation.
const (
	vectorCode    = "ABCDE-FGHJK-MNPQR-STVWX"
	vectorMailbox = "bd9848e4adcaf42e50c3a6bed80fd932ab61249ecbaa043e4c21786a5dfdc9f3"
	vectorKey     = "cbdf4d8d1ebe95d9e537f2f0457f990d1849698b4127035c1883c8bc0695dd7d"
)

func TestDeriveVector(t *testing.T) {
	p, err := Derive(vectorCode)
	if err != nil {
		t.Fatal(err)
	}
	if p.Mailbox != vectorMailbox || hex.EncodeToString(p.key) != vectorKey {
		t.Fatalf("mailbox=%s key=%x", p.Mailbox, p.key)
	}
	if p.Reply == p.Mailbox || len(p.Reply) != 64 {
		t.Fatalf("reply mailbox must differ from the upload mailbox, got %s", p.Reply)
	}
}

func TestConfirmPostsToReplyMailbox(t *testing.T) {
	p, _ := Derive(vectorCode)
	var gotPath string
	var gotBody []byte
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotBody, _ = io.ReadAll(r.Body)
		w.WriteHeader(http.StatusCreated)
	}))
	defer ts.Close()

	if err := Confirm(context.Background(), ts.Client(), ts.URL, p, 3); err != nil {
		t.Fatal(err)
	}
	if gotPath != "/v1/mailbox/"+p.Reply {
		t.Fatalf("posted to %s", gotPath)
	}
	out, err := p.Open(gotBody)
	if err != nil || !strings.Contains(string(out), `"event":"pc_ack"`) || !strings.Contains(string(out), `"uploads":3`) {
		t.Fatalf("ack payload %q, %v", out, err)
	}
}

// Sealed by the Android app's RelayCrypto for vectorCode, so this catches the two sides drifting apart.
const androidBlob = "0199cc9a5b3da3e4f02faaab924db4fd04c6a3448d3a4b53ad8451e5c1dd14c7dffd8b76cc4f057094bac7b0245c31c47ba2835386f4a6a092110368808802860fd2289e55f2e683a071c574a4f4df618e2d9deb772211cf166ebc"

func TestOpensBlobSealedByAndroid(t *testing.T) {
	p, _ := Derive(vectorCode)
	blob, _ := hex.DecodeString(androidBlob)
	got, err := p.Open(blob)
	if err != nil || string(got) != "{\"t\":\"2026-09-21T13:00:00Z\",\"event\":\"unlock\"}\n" {
		t.Fatalf("got %q, %v", got, err)
	}
}

func TestNormalizeAcceptsHumanTyping(t *testing.T) {
	a, _ := Derive("abcde fghjk-mnpqr stvwx")
	b, _ := Derive(vectorCode)
	if a.Mailbox != b.Mailbox {
		t.Fatal("case, spaces and dashes should not matter")
	}
	o, _ := Derive("O0000-00000-00000-0000L")
	z, _ := Derive("00000-00000-00000-00001")
	if o.Mailbox != z.Mailbox {
		t.Fatal("O and L should read as 0 and 1")
	}
	for _, bad := range []string{"ABCDE", "ABCDE-FGHJK-MNPQR-STVWU", "ABCDE-FGHJK-MNPQR-STVWXY"} {
		if _, err := Derive(bad); err == nil {
			t.Errorf("%q should be rejected", bad)
		}
	}
}

func TestNewCodeIsValidAndRandom(t *testing.T) {
	a, b := NewCode(), NewCode()
	if _, err := Derive(a); err != nil || a == b || len(a) != 23 {
		t.Fatalf("a=%q b=%q err=%v", a, b, err)
	}
}

func TestSealOpenRoundTripAndTamper(t *testing.T) {
	p, _ := Derive(vectorCode)
	blob, err := p.Seal([]byte("line1\nline2\n"))
	if err != nil {
		t.Fatal(err)
	}
	got, err := p.Open(blob)
	if err != nil || string(got) != "line1\nline2\n" {
		t.Fatalf("got %q, %v", got, err)
	}
	blob[len(blob)-1] ^= 1
	if _, err := p.Open(blob); err == nil {
		t.Fatal("tampered blob should fail")
	}
	other, _ := Derive("00000-00000-00000-00000")
	fresh, _ := p.Seal([]byte("x"))
	if _, err := other.Open(fresh); err == nil {
		t.Fatal("another pairing's key must not open it")
	}
}

// fakeRelay mimics the relay API closely enough to exercise Pull, with a page size of 2.
type fakeRelay struct {
	mu    sync.Mutex
	items []relayItem
}

func (f *fakeRelay) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	switch r.Method {
	case http.MethodGet:
		page := f.items[:min(2, len(f.items))]
		json.NewEncoder(w).Encode(map[string]any{"items": page, "more": len(f.items) > 2})
	case http.MethodDelete:
		upto := r.URL.Query().Get("upto")
		for len(f.items) > 0 && f.items[0].ID <= upto {
			f.items = f.items[1:]
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func TestPullStoresDecryptedAndAcks(t *testing.T) {
	p, _ := Derive(vectorCode)
	relay := &fakeRelay{}
	for i, body := range []string{"a\n", "b\n", "c\n"} {
		blob, _ := p.Seal([]byte(body))
		relay.items = append(relay.items, relayItem{ID: string(rune('1' + i)), Data: base64.StdEncoding.EncodeToString(blob)})
	}
	relay.items = append(relay.items, relayItem{ID: "4", Data: base64.StdEncoding.EncodeToString([]byte("junk"))})
	ts := httptest.NewServer(relay)
	defer ts.Close()

	var got []string
	var bad []string
	n, err := Pull(context.Background(), ts.Client(), ts.URL, p,
		func(id string, jsonl []byte) error { got = append(got, id+":"+string(jsonl)); return nil },
		func(id string, err error) { bad = append(bad, id) })
	if err != nil || n != 3 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	if strings.Join(got, "") != "1:a\n2:b\n3:c\n" || strings.Join(bad, ",") != "4" || len(relay.items) != 0 {
		t.Fatalf("got=%q bad=%v left=%d", got, bad, len(relay.items))
	}
}

func TestPullKeepsItemsWhenStoreFails(t *testing.T) {
	p, _ := Derive(vectorCode)
	blob, _ := p.Seal([]byte("a\n"))
	relay := &fakeRelay{items: []relayItem{{ID: "1", Data: base64.StdEncoding.EncodeToString(blob)}}}
	ts := httptest.NewServer(relay)
	defer ts.Close()
	_, err := Pull(context.Background(), ts.Client(), ts.URL, p,
		func(string, []byte) error { return errors.New("disk full") }, func(string, error) {})
	if err == nil || len(relay.items) != 1 {
		t.Fatalf("err=%v left=%d; unstored items must stay on the relay", err, len(relay.items))
	}
}
