package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestClassifyTellsWorkFromWatchingInsideOneBrowser(t *testing.T) {
	cases := []struct{ app, title, want string }{
		{"msedge.exe", "Lecture 5 - Operating Systems - YouTube - Microsoft Edge", CatEntertainment},
		{"msedge.exe", "planj · GitLab - Microsoft Edge", CatFocus},
		{"msedge.exe", "Instagram - Microsoft Edge", CatSocial},
		{"msedge.exe", "(3) Home / X - Microsoft Edge", CatSocial},
		{"chrome.exe", "WhatsApp - Google Chrome", CatChat},
		{"Code.exe", "features.py - planj - Visual Studio Code", CatFocus},
		{"Acrobat.exe", "notes.pdf - Adobe Acrobat", CatFocus},
		{"Notepad.exe", "Untitled - Notepad", CatOther},
		{"explorer.exe", "", CatOther},
	}
	for _, c := range cases {
		if got := Classify(c.app, c.title, nil); got != c.want {
			t.Errorf("%s %q: got %s, want %s", c.app, c.title, got, c.want)
		}
	}
}

func TestUserRulesWin(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "categories.txt")
	os.WriteFile(path, []byte("# mine\nfocus: operating systems\nentertainment: notepad\nnonsense line\nbogus: x\n"), 0o600)
	rules := LoadRules(path)
	if len(rules) != 2 {
		t.Fatalf("rules: %v", rules)
	}
	if got := Classify("msedge.exe", "Lecture 5 - Operating Systems - YouTube", rules); got != CatFocus {
		t.Errorf("user focus rule should beat youtube: %s", got)
	}
	if got := Classify("Notepad.exe", "Untitled - Notepad", rules); got != CatEntertainment {
		t.Errorf("user rule: %s", got)
	}
	if LoadRules(filepath.Join(dir, "missing.txt")) != nil {
		t.Error("missing file should mean no rules")
	}
}

func TestWatchingAndReadingGetLongerAllowance(t *testing.T) {
	def := 3 * time.Minute
	if IdleAllowance(CatEntertainment, "msedge.exe", "YouTube", def) != 30*time.Minute {
		t.Error("watching should allow 30 minutes")
	}
	if IdleAllowance(CatFocus, "Acrobat.exe", "notes.pdf", def) != 10*time.Minute {
		t.Error("reading should allow 10 minutes")
	}
	if IdleAllowance(CatFocus, "Code.exe", "main.go", def) != def {
		t.Error("typing work keeps the default")
	}
}

func TestRecorderSplitsOnCategoryAndHonoursAllowance(t *testing.T) {
	r := &Recorder{IdleAfter: 3 * time.Minute, MaxSpan: time.Hour, MaxGap: time.Minute}
	t0 := time.Date(2026, 9, 29, 20, 0, 0, 0, time.UTC)
	step := 5 * time.Second
	var spans []Span
	// 10 minutes of GitLab with typing, then 10 minutes of YouTube with no input at all.
	for i := 0; i < 120; i++ {
		spans = append(spans, r.ObserveFull(t0.Add(time.Duration(i)*step), Observation{App: "msedge.exe", Cat: CatFocus})...)
	}
	for i := 120; i < 240; i++ {
		idleFor := time.Duration(i-120) * step
		spans = append(spans, r.ObserveFull(t0.Add(time.Duration(i)*step), Observation{App: "msedge.exe", Cat: CatEntertainment, IdleFor: idleFor, IdleAfter: 30 * time.Minute})...)
	}
	spans = append(spans, r.Flush()...)
	if len(spans) != 2 {
		t.Fatalf("want a focus span then a watching span, got %+v", spans)
	}
	if spans[0].Cat != CatFocus || spans[1].Cat != CatEntertainment {
		t.Fatalf("categories: %+v", spans)
	}
	if spans[1].Idle {
		t.Fatal("10 minutes of watching without input must not count as idle")
	}
}

func TestNamesComeFromRulesNotTitles(t *testing.T) {
	cat, name := ClassifyNamed("msedge.exe", "My secret doc about something - YouTube - Microsoft Edge", nil)
	if cat != CatEntertainment || name != "YouTube" {
		t.Fatalf("got %s %q", cat, name)
	}
	if _, name := ClassifyNamed("msedge.exe", "Some private page - Microsoft Edge", nil); name != "" {
		t.Fatalf("an unrecognised page must not produce a name, got %q", name)
	}
	if AppName("msedge.exe") != "Edge" || AppName("Code.exe") != "VS Code" || AppName("Figma.exe") != "Figma" {
		t.Fatal("app names")
	}
}
