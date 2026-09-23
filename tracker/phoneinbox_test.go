package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestPairingIsCreatedOnceThenReused(t *testing.T) {
	root := t.TempDir()
	if _, ok, _ := loadPairing(root); ok {
		t.Fatal("no pairing expected yet")
	}
	a, err := loadOrCreatePairing(root)
	if err != nil {
		t.Fatal(err)
	}
	b, err := loadOrCreatePairing(root)
	if err != nil || a.Code != b.Code || a.Mailbox != b.Mailbox {
		t.Fatalf("pairing changed: %q vs %q (%v)", a.Code, b.Code, err)
	}
}

func TestRelayURLOverride(t *testing.T) {
	root := t.TempDir()
	if relayURL(root) != defaultRelayURL {
		t.Fatal("default expected")
	}
	os.WriteFile(filepath.Join(root, "relay_url.txt"), []byte(" http://localhost:8080\n"), 0o600)
	if relayURL(root) != "http://localhost:8080" {
		t.Fatalf("got %q", relayURL(root))
	}
}

func TestAppendPhoneDataTerminatesLines(t *testing.T) {
	dir := t.TempDir()
	day := time.Date(2026, 9, 21, 12, 0, 0, 0, time.UTC)
	appendPhoneData(dir, day, []byte(`{"a":1}`))
	appendPhoneData(dir, day, []byte("{\"a\":2}\n"))
	b, _ := os.ReadFile(filepath.Join(dir, "planj-phone-relay-2026-09-21.jsonl"))
	if string(b) != "{\"a\":1}\n{\"a\":2}\n" {
		t.Fatalf("got %q", b)
	}
}
