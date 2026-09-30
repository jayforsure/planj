package main

import (
	"encoding/json"
	"time"
)

// LiveNow is what is in front of the person on the PC at this moment.
type LiveNow struct {
	Name   string `json:"name"`   // "YouTube", "VS Code", a site; never a window title
	Cat    string `json:"cat"`    // focus | entertainment | social | chat | other
	Since  string `json:"since"`  // when this started, RFC 3339
	Idle   bool   `json:"idle"`   // no input for longer than this window's allowance
	Locked bool   `json:"locked"` // lock screen or no window
}

// LiveStatus is the latest status the phone shows: now, plus today's totals to the second.
type LiveStatus struct {
	Event string     `json:"event"` // "pc_live"
	T     string     `json:"t"`
	Now   LiveNow    `json:"now"`
	Day   DaySummary `json:"day"`
}

// LiveTracker turns each poll into a LiveNow, remembering since when the same thing has
// been in front: recorded spans are cut every minute, so their start would say too little.
type LiveTracker struct {
	key   string
	since time.Time
}

// Observe records one poll and says whether what is in front changed.
func (l *LiveTracker) Observe(t time.Time, o Observation, idle bool) (LiveNow, bool) {
	now := LiveNow{Name: DisplayName(o.App, o.Name), Cat: o.Cat, Idle: idle, Locked: Locked(o.App)}
	if now.Cat == "" {
		now.Cat = CatOther
	}
	key := now.Name + "|" + now.Cat + "|" + boolKey(now.Idle) + boolKey(now.Locked)
	changed := key != l.key
	if changed {
		l.key = key
		l.since = t
		if idle {
			l.since = t.Add(-o.IdleFor) // away since the last input, not since we noticed
		}
	}
	now.Since = l.since.UTC().Format(time.RFC3339)
	return now, changed
}

func boolKey(b bool) string {
	if b {
		return "1"
	}
	return "0"
}

// BuildLive assembles the status line sent to the phone, counting the span in progress.
func BuildLive(dir string, t time.Time, now LiveNow, cur *Span) []byte {
	var extra []Span
	if cur != nil && cur.End.After(cur.Start) {
		extra = append(extra, *cur)
	}
	st := LiveStatus{Event: "pc_live", T: t.UTC().Format(time.RFC3339), Now: now,
		Day: SummarizeDayWith(dir, t, t.Location(), extra)}
	b, _ := json.Marshal(st)
	return append(b, '\n')
}
