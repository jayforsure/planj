//go:build windows

package main

import (
	"context"
	"log"
	"net/http"
	"path/filepath"
	"time"
)

const pullEvery = 5 * time.Minute

func showPairing(root string) {
	p, err := loadOrCreatePairing(root)
	if err != nil {
		messageBox("Could not set up phone pairing: " + err.Error())
		return
	}
	messageBox("Pair your phone\n\nIn the planj phone app, tap \"Pair with PC\" and enter:\n\n        " + p.Code +
		"\n\nKeep this code private: it is the key that encrypts your synced data.")
}

// pullLoop collects phone uploads from the relay. It re-reads the pairing each round,
// so pairing after the tracker started works without a restart.
func pullLoop(root string) {
	client := &http.Client{Timeout: time.Minute}
	inbox := filepath.Join(root, "phone")
	for {
		p, ok, err := loadPairing(root)
		base := relayURL(root)
		switch {
		case err != nil:
			log.Printf("pairing: %v", err)
		case ok && base != "":
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
			n, err := Pull(ctx, client, base, p,
				func(_ string, jsonl []byte) error { return appendPhoneData(inbox, time.Now(), jsonl) },
				func(id string, err error) { log.Printf("dropped undecryptable relay item %s: %v", id, err) })
			cancel()
			if err != nil {
				log.Printf("relay pull: %v", err)
			} else if n > 0 {
				log.Printf("relay pull: stored %d uploads", n)
			}
		}
		time.Sleep(pullEvery)
	}
}
