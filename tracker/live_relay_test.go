package main

import (
	"bytes"
	"context"
	"net/http"
	"os"
	"testing"
	"time"
)

// End-to-end against a running relay, e.g.
//
//	PLANJ_RELAY_E2E=https://planj-relay-production.up.railway.app go test -run TestLiveRelayRoundTrip
//
// Skipped by default so the normal test run stays offline.
func TestLiveRelayRoundTrip(t *testing.T) {
	base := os.Getenv("PLANJ_RELAY_E2E")
	if base == "" {
		t.Skip("set PLANJ_RELAY_E2E to run")
	}
	p, err := Derive(NewCode()) // a throwaway mailbox nobody else knows
	if err != nil {
		t.Fatal(err)
	}
	client := &http.Client{Timeout: 30 * time.Second}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()

	payload := []byte(`{"t":"2026-09-23T12:00:00Z","event":"unlock"}` + "\n")
	blob, err := p.Seal(payload)
	if err != nil {
		t.Fatal(err)
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, base+"/v1/mailbox/"+p.Mailbox, bytes.NewReader(blob))
	req.Header.Set("Content-Type", "application/octet-stream")
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("upload: %s", resp.Status)
	}

	var got [][]byte
	n, err := Pull(ctx, client, base, p,
		func(_ string, jsonl []byte) error { got = append(got, jsonl); return nil },
		func(id string, err error) { t.Errorf("undecryptable %s: %v", id, err) })
	if err != nil || n != 1 || len(got) != 1 || !bytes.Equal(got[0], payload) {
		t.Fatalf("pull: n=%d err=%v got=%q", n, err, got)
	}

	// Pull acknowledges what it stored, so the mailbox must now be empty.
	again, err := Pull(ctx, client, base, p, func(string, []byte) error { return nil }, func(string, error) {})
	if err != nil || again != 0 {
		t.Fatalf("second pull: n=%d err=%v; the relay should have deleted the blob", again, err)
	}
}
