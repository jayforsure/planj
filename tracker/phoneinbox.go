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

func readTrimmed(path string) (string, error) {
	b, err := os.ReadFile(path)
	return strings.TrimSpace(string(b)), err
}

func relayURL(root string) string {
	if s, err := readTrimmed(filepath.Join(root, "relay_url.txt")); err == nil {
		return s
	}
	return defaultRelayURL
}

func pairingPath(root string) string { return filepath.Join(root, "pairing.txt") }

// loadPairing reads the key file that `planj signin` writes. Missing means not signed in.
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
