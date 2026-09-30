package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestLiveTrackerKnowsSinceWhen(t *testing.T) {
	var l LiveTracker
	t0 := time.Date(2026, 9, 30, 7, 0, 0, 0, time.UTC)
	yt := Observation{App: "msedge.exe", Cat: CatEntertainment, Name: "YouTube"}
	now, changed := l.Observe(t0, yt, false)
	if !changed || now.Name != "YouTube" || now.Since != "2026-09-30T07:00:00Z" {
		t.Fatalf("first: %+v %v", now, changed)
	}
	// ten minutes later, still YouTube: same "since", no change
	now, changed = l.Observe(t0.Add(10*time.Minute), yt, false)
	if changed || now.Since != "2026-09-30T07:00:00Z" {
		t.Fatalf("still YouTube: %+v %v", now, changed)
	}
	// switch to a page no rule knows: its site
	now, changed = l.Observe(t0.Add(11*time.Minute), Observation{App: "msedge.exe", Cat: CatOther, Name: "lms.utar.edu.my"}, false)
	if !changed || now.Name != "lms.utar.edu.my" || now.Since != "2026-09-30T07:11:00Z" {
		t.Fatalf("switch: %+v %v", now, changed)
	}
	// idle: away since the last input, 4 minutes before we noticed
	now, _ = l.Observe(t0.Add(20*time.Minute), Observation{App: "Code.exe", Cat: CatFocus, Name: "VS Code", IdleFor: 4 * time.Minute}, true)
	if !now.Idle || now.Since != "2026-09-30T07:16:00Z" {
		t.Fatalf("idle: %+v", now)
	}
	now, _ = l.Observe(t0.Add(21*time.Minute), Observation{App: "LockApp.exe"}, true)
	if !now.Locked {
		t.Fatalf("lock screen: %+v", now)
	}
}

func TestBuildLiveCountsTheSpanInProgress(t *testing.T) {
	dir := t.TempDir()
	loc := time.FixedZone("MYT", 8*3600)
	os.WriteFile(filepath.Join(dir, "2026-09-30.jsonl"),
		[]byte(`{"start":"2026-09-30T01:00:00.000Z","end":"2026-09-30T01:10:00.000Z","app":"Code.exe","cat":"focus","name":"VS Code","idle":false}`+"\n"), 0o600)
	t0 := time.Date(2026, 9, 30, 9, 30, 0, 0, loc) // 01:30 UTC
	cur := Span{Start: t0.Add(-5 * time.Minute), End: t0, App: "msedge.exe", Cat: CatEntertainment, Name: "YouTube"}
	var st LiveStatus
	if err := json.Unmarshal(BuildLive(dir, t0, LiveNow{Name: "YouTube"}, &cur), &st); err != nil {
		t.Fatal(err)
	}
	mins := map[string]float64{}
	for _, a := range st.Day.Apps {
		mins[a[0].(string)] = a[1].(float64)
	}
	if st.Event != "pc_live" || mins["VS Code"] != 10 || mins["YouTube"] != 5 {
		t.Fatalf("live day: %+v", st.Day.Apps)
	}
}
