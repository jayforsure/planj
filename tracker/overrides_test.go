package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestPhoneChoicesResortThePastToo(t *testing.T) {
	dir := t.TempDir()
	LoadOverrides(dir)
	defer func() { overrides = &CategoryOverrides{m: map[string]string{}} }()
	loc := time.FixedZone("MYT", 8*3600)
	os.WriteFile(filepath.Join(dir, "2026-09-30.jsonl"), []byte(strings.Join([]string{
		// 09:00-09:40 on the course site, which the tracker had as "other"
		`{"start":"2026-09-30T01:00:00.000Z","end":"2026-09-30T01:40:00.000Z","app":"msedge.exe","cat":"other","name":"lms.utar.edu.my","idle":false}`,
		// 09:40-09:40:20 a glance at YouTube: too short to be its own session
		`{"start":"2026-09-30T01:40:00.000Z","end":"2026-09-30T01:40:20.000Z","app":"msedge.exe","cat":"entertainment","name":"YouTube","idle":false}`,
		// back on the course site 09:40:20-10:00, then VS Code 10:00-10:30 in two pieces
		`{"start":"2026-09-30T01:40:20.000Z","end":"2026-09-30T02:00:00.000Z","app":"msedge.exe","cat":"other","name":"lms.utar.edu.my","idle":false}`,
		`{"start":"2026-09-30T02:00:00.000Z","end":"2026-09-30T02:15:00.000Z","app":"Code.exe","cat":"focus","name":"VS Code","idle":false}`,
		`{"start":"2026-09-30T02:15:00.000Z","end":"2026-09-30T02:30:00.000Z","app":"Code.exe","cat":"focus","name":"VS Code","idle":false}`,
	}, "\n")+"\n"), 0o600)
	day := time.Date(2026, 9, 30, 12, 0, 0, 0, loc)

	// the phone says the course site counts as focus
	rest, changed := TakeRules([]byte(`{"t":"x","event":"unlock"}` + "\n" + `{"event":"pc_rule","name":"lms.utar.edu.my","cat":"focus"}` + "\n"))
	if !changed || strings.Contains(string(rest), "pc_rule") || !strings.Contains(string(rest), "unlock") {
		t.Fatalf("rule not taken out and applied: %q %v", rest, changed)
	}
	if _, again := TakeRules([]byte(`{"event":"pc_rule","name":"lms.utar.edu.my","cat":"focus"}` + "\n")); again {
		t.Fatal("the same choice twice is not a change")
	}
	if _, bad := TakeRules([]byte(`{"event":"pc_rule","name":"x","cat":"nonsense"}` + "\n")); bad {
		t.Fatal("an unknown category is ignored")
	}

	got := SummarizeDay(dir, day, loc)
	cats := map[string]string{}
	for _, a := range got.Apps {
		cats[a[0].(string)] = a[2].(string)
	}
	if cats["lms.utar.edu.my"] != CatFocus {
		t.Fatalf("past records re-sorted: %v", cats)
	}
	if len(got.Spans) == 0 || got.Spans[0][2] != CatFocus {
		t.Fatalf("timeline re-sorted: %v", got.Spans)
	}
	// sessions: the course site 09:00-10:00 is one (the 20 s glance between is skipped), then VS Code 10:00-10:30
	want := [][3]any{{540, 600, "lms.utar.edu.my"}, {600, 630, "VS Code"}}
	if len(got.Sessions) != len(want) {
		t.Fatalf("sessions: %v", got.Sessions)
	}
	for i := range want {
		if got.Sessions[i] != want[i] {
			t.Fatalf("session %d: %v, want %v", i, got.Sessions[i], want[i])
		}
	}
	// and the choice survives a restart
	LoadOverrides(dir)
	if c, _ := overrides.Get("lms.utar.edu.my"); c != CatFocus {
		t.Fatal("choice not saved")
	}
}
