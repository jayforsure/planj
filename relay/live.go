package main

import (
	"io"
	"net/http"
	"sync"
	"time"
)

// The live slot holds one sealed blob per mailbox: the PC's latest "what is in front now"
// and today's totals. Each write replaces the last, so nothing piles up, and it lives only
// in memory: after a restart the PC fills it again within seconds.
const (
	maxLive      = 256 << 10
	maxLiveSlots = 200
	liveTTL      = 10 * time.Minute // a PC that stopped writing is not "now" any more
)

type liveSlot struct {
	data []byte
	at   time.Time
}

type liveStore struct {
	mu    sync.Mutex
	slots map[string]liveSlot
}

func (l *liveStore) put(box string, data []byte, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.slots == nil {
		l.slots = map[string]liveSlot{}
	}
	if _, ok := l.slots[box]; !ok && len(l.slots) >= maxLiveSlots {
		for k, v := range l.slots { // make room by dropping slots nobody has written lately
			if now.Sub(v.at) > liveTTL {
				delete(l.slots, k)
			}
		}
		if len(l.slots) >= maxLiveSlots {
			return false
		}
	}
	l.slots[box] = liveSlot{data: data, at: now}
	return true
}

func (l *liveStore) get(box string, now time.Time) ([]byte, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	slot, ok := l.slots[box]
	if !ok || now.Sub(slot.at) > liveTTL {
		return nil, false
	}
	return slot.data, true
}

func (s *server) putLive(w http.ResponseWriter, r *http.Request) {
	box := r.PathValue("box")
	if !boxRe.MatchString(box) {
		http.Error(w, "bad mailbox id", http.StatusBadRequest)
		return
	}
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxLive))
	if err != nil {
		http.Error(w, "blob too large", http.StatusRequestEntityTooLarge)
		return
	}
	if len(data) == 0 {
		http.Error(w, "empty blob", http.StatusBadRequest)
		return
	}
	if !s.live.put(box, data, time.Now()) {
		http.Error(w, "relay is full", http.StatusInsufficientStorage)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) getLive(w http.ResponseWriter, r *http.Request) {
	box := r.PathValue("box")
	if !boxRe.MatchString(box) {
		http.Error(w, "bad mailbox id", http.StatusBadRequest)
		return
	}
	data, ok := s.live.get(box, time.Now())
	if !ok {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(data)
}
