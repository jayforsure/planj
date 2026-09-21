package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var t0 = time.Date(2026, 9, 21, 9, 0, 0, 0, time.UTC)

func sec(n int) time.Time { return t0.Add(time.Duration(n) * time.Second) }

func newRec() *Recorder {
	return &Recorder{IdleAfter: 3 * time.Minute, MaxSpan: time.Minute, MaxGap: 30 * time.Second}
}

func TestAppSwitchClosesSpan(t *testing.T) {
	r := newRec()
	r.Observe(sec(0), "Code.exe", 0)
	r.Observe(sec(5), "Code.exe", 0)
	got := r.Observe(sec(10), "msedge.exe", 0)
	want := []Span{{Start: sec(0), End: sec(10), App: "Code.exe"}}
	if len(got) != 1 || got[0] != want[0] {
		t.Fatalf("got %+v, want %+v", got, want)
	}
}

// observeUntil polls every 5s up to end, with the user's last input at lastInput.
func observeUntil(r *Recorder, end, lastInput int) []Span {
	var got []Span
	for s := 0; s <= end; s += 5 {
		idleFor := time.Duration(0)
		if s > lastInput {
			idleFor = time.Duration(s-lastInput) * time.Second
		}
		got = append(got, r.Observe(sec(s), "Code.exe", idleFor)...)
	}
	return got
}

func TestIdleIsBackdatedToLastInput(t *testing.T) {
	r := newRec()
	r.MaxSpan = time.Hour
	got := observeUntil(r, 200, 20) // idle from 200s (180s after last input at 20s)
	if len(got) != 1 || got[0].Idle || !got[0].End.Equal(sec(20)) {
		t.Fatalf("active span should end at the last input, got %+v", got)
	}
	if !r.cur.Idle || !r.cur.Start.Equal(sec(20)) {
		t.Fatalf("idle span should start at the last input, got %+v", r.cur)
	}
}

func TestSplitDoesNotCommitPossiblyIdleTime(t *testing.T) {
	r := newRec() // MaxSpan 60s is shorter than the 3 min idle threshold
	got := observeUntil(r, 190, 10)
	var active time.Duration
	for _, s := range got {
		if !s.Idle {
			active += s.End.Sub(s.Start)
		}
	}
	if active != 10*time.Second {
		t.Fatalf("only the 10s before the last input is active, got %v in %+v", active, got)
	}
	if !r.cur.Idle || !r.cur.Start.Equal(sec(10)) {
		t.Fatalf("idle span should start at the last input, got %+v", r.cur)
	}
}

func TestLongSpanIsSplit(t *testing.T) {
	r := newRec()
	var got []Span
	for s := 0; s <= 130; s += 5 {
		got = append(got, r.Observe(sec(s), "Code.exe", 0)...)
	}
	if len(got) != 2 || !got[0].End.Equal(sec(60)) || !got[1].Start.Equal(sec(60)) || !got[1].End.Equal(sec(120)) {
		t.Fatalf("got %+v", got)
	}
}

func TestSleepGapIsNotCounted(t *testing.T) {
	r := newRec()
	r.Observe(sec(0), "Code.exe", 0)
	r.Observe(sec(5), "Code.exe", 0)
	got := r.Observe(sec(3600), "Code.exe", 0)
	if len(got) != 1 || !got[0].End.Equal(sec(5)) {
		t.Fatalf("span should end before the gap, got %+v", got)
	}
	if !r.cur.Start.Equal(sec(3600)) {
		t.Fatalf("new span should start after the gap, got %+v", r.cur)
	}
}

func TestFlush(t *testing.T) {
	r := newRec()
	if r.Flush() != nil {
		t.Fatal("flush on empty recorder should return nothing")
	}
	r.Observe(sec(0), "Code.exe", 0)
	r.Observe(sec(15), "Code.exe", 0)
	got := r.Flush()
	if len(got) != 1 || !got[0].End.Equal(sec(15)) || r.cur != nil {
		t.Fatalf("got %+v", got)
	}
}

func TestWriterFilesByLocalDay(t *testing.T) {
	kl := time.FixedZone("KL", 8*3600)
	dir := t.TempDir()
	w := Writer{Dir: dir, Loc: kl}
	late := time.Date(2026, 9, 21, 16, 30, 0, 0, time.UTC) // 00:30 on the 22nd in KL
	if err := w.Write([]Span{{Start: late, End: late.Add(time.Minute), App: "Code.exe", Idle: true}}); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(filepath.Join(dir, "2026-09-22.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	var rec record
	if err := json.Unmarshal([]byte(strings.TrimSpace(string(b))), &rec); err != nil {
		t.Fatal(err)
	}
	want := record{Start: "2026-09-21T16:30:00.000Z", End: "2026-09-21T16:31:00.000Z", App: "Code.exe", Idle: true}
	if rec != want {
		t.Fatalf("got %+v, want %+v", rec, want)
	}
}
