//go:build windows

package main

import (
	"context"
	"log"
	"net/http"
	"path/filepath"
	"time"
)

const liveHeartbeat = 30 * time.Second // totals refresh on the phone at least this often

// liveUpdate is one poll's result, handed from the tracking loop to the sender.
type liveUpdate struct {
	at      time.Time
	now     LiveNow
	changed bool
	cur     *Span
}

// liveLoop sends the latest status to the relay whenever what is in front changes, and on a
// heartbeat otherwise. It only ever looks at the newest update, so a slow network never
// builds a queue: the phone always gets the present, not a backlog.
func liveLoop(root string, updates <-chan liveUpdate) {
	client := &http.Client{Timeout: 10 * time.Second}
	dir := filepath.Join(root, "activity")
	var (
		p         Pairing
		ok        bool
		loadedAt  time.Time
		lastSent  time.Time
		lastError time.Time
		pending   bool
	)
	for u := range updates {
		if u.changed {
			pending = true
		}
		if !pending && time.Since(lastSent) < liveHeartbeat {
			continue
		}
		if time.Since(loadedAt) > time.Minute { // signing in or out applies within a minute
			var err error
			p, ok, err = loadPairing(root)
			loadedAt = time.Now()
			if err != nil {
				ok = false
			}
		}
		if !ok {
			continue
		}
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		err := SendLive(ctx, client, relayURL(root), p, BuildLive(dir, u.at, u.now, u.cur))
		cancel()
		if err != nil {
			if time.Since(lastError) > 10*time.Minute { // offline for a while logs once, not every second
				log.Printf("live: %v", err)
				lastError = time.Now()
			}
			continue // stays pending, so the change is sent as soon as the network is back
		}
		pending = false
		lastSent = time.Now()
	}
}

// offerLive hands the newest update to the sender, replacing one it has not taken yet.
func offerLive(ch chan liveUpdate, u liveUpdate) {
	select {
	case ch <- u:
		return
	default:
	}
	select {
	case old := <-ch:
		u.changed = u.changed || old.changed
	default:
	}
	select {
	case ch <- u:
	default:
	}
}
