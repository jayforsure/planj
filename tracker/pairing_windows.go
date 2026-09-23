//go:build windows

package main

import (
	"context"
	"log"
	"net/http"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

const (
	pullEvery    = 5 * time.Minute
	syncTimeout  = 3 * time.Minute
	confirmEvery = 15 * time.Minute
)

var lastConfirm time.Time

func showPairing(root string) {
	p, err := loadOrCreatePairing(root)
	if err != nil {
		messageBox("Could not set up phone pairing: " + err.Error())
		return
	}
	messageBox("Pair your phone\n\nIn the planj phone app, tap \"Pair with PC\" and enter:\n\n        " + p.Code +
		"\n\nThe phone will show \"PC confirmed\" once data arrives here.\n\nKeep this code private: it is the key that encrypts your synced data.")
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
			pullOnce(root, inbox, client, base, p)
		}
		time.Sleep(pullEvery)
	}
}

func pullOnce(root, inbox string, client *http.Client, base string, p Pairing) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	n, err := Pull(ctx, client, base, p,
		func(_ string, jsonl []byte) error { return appendPhoneData(inbox, time.Now(), jsonl) },
		func(id string, err error) { log.Printf("dropped undecryptable relay item %s: %v", id, err) })
	if err != nil {
		log.Printf("relay pull: %v", err)
		return
	}

	// Confirm regularly, not only when data arrived: a phone with nothing to send still
	// needs to learn that a PC with the same code is listening.
	if n > 0 || time.Since(lastConfirm) >= confirmEvery {
		if err := Confirm(ctx, client, base, p, n); err != nil {
			log.Printf("confirm: %v", err)
		} else {
			lastConfirm = time.Now()
		}
	}
	if n > 0 {
		log.Printf("relay pull: stored %d uploads", n)
		runSyncCommand(root)
	}
}

// runSyncCommand runs whatever sync_cmd.txt contains, so new phone data can be
// imported without anyone typing a command. Missing file means no import step.
func runSyncCommand(root string) {
	b, err := readTrimmed(filepath.Join(root, "sync_cmd.txt"))
	if err != nil || b == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), syncTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, "cmd.exe", "/C", b)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	out, err := cmd.CombinedOutput()
	if err != nil {
		log.Printf("sync command failed: %v: %s", err, strings.TrimSpace(string(out)))
		return
	}
	log.Printf("sync command ok: %s", strings.Join(strings.Fields(string(out)), " "))
}
