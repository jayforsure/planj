package main

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// defaultRelayURL is where paired phones upload; relay_url.txt in the data folder overrides it.
const defaultRelayURL = "https://planj-relay-production.up.railway.app"

func relayURL(root string) string {
	if b, err := os.ReadFile(filepath.Join(root, "relay_url.txt")); err == nil {
		return strings.TrimSpace(string(b))
	}
	return defaultRelayURL
}

func pairingPath(root string) string { return filepath.Join(root, "pairing.txt") }

// loadOrCreatePairing returns this PC's pairing, creating a fresh code the first time.
func loadOrCreatePairing(root string) (Pairing, error) {
	if p, ok, err := loadPairing(root); ok || err != nil {
		return p, err
	}
	if err := os.MkdirAll(root, 0o700); err != nil {
		return Pairing{}, err
	}
	code := NewCode()
	if err := os.WriteFile(pairingPath(root), []byte(code+"\n"), 0o600); err != nil {
		return Pairing{}, err
	}
	return Derive(code)
}

func loadPairing(root string) (Pairing, bool, error) {
	b, err := os.ReadFile(pairingPath(root))
	if errors.Is(err, os.ErrNotExist) {
		return Pairing{}, false, nil
	}
	if err != nil {
		return Pairing{}, false, err
	}
	p, err := Derive(strings.TrimSpace(string(b)))
	return p, err == nil, err
}

// appendPhoneData adds a decrypted upload to today's inbox file, which `planj sync` imports.
// Appending may duplicate lines if a pull is retried; the importer skips duplicates.
func appendPhoneData(dir string, now time.Time, jsonl []byte) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	if len(jsonl) > 0 && jsonl[len(jsonl)-1] != '\n' {
		jsonl = append(jsonl, '\n')
	}
	f, err := os.OpenFile(filepath.Join(dir, "planj-phone-relay-"+now.Format("2006-01-02")+".jsonl"),
		os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	if _, err := f.Write(jsonl); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}
