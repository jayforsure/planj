package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestSummarizeDayMergesPresentTimeByCategory(t *testing.T) {
	dir := t.TempDir()
	loc := time.FixedZone("MYT", 8*3600)
	lines := []string{
		// the day before, running past midnight into our day (23:50-00:20 local)
		`{"start":"2026-09-28T15:50:00.000Z","end":"2026-09-28T16:20:00.000Z","app":"Code.exe","cat":"focus","idle":false}`,
	}
	os.WriteFile(filepath.Join(dir, "2026-09-28.jsonl"), []byte(strings.Join(lines, "\n")+"\n"), 0o600)
	lines = []string{
		// 09:00-09:30 focus and 09:31-10:00 focus merge (gap under 2 minutes)
		`{"start":"2026-09-29T01:00:00.000Z","end":"2026-09-29T01:30:00.000Z","app":"Code.exe","cat":"focus","idle":false}`,
		`{"start":"2026-09-29T01:31:00.000Z","end":"2026-09-29T02:00:00.000Z","app":"msedge.exe","cat":"focus","idle":false}`,
		// idle is dropped
		`{"start":"2026-09-29T02:00:00.000Z","end":"2026-09-29T02:30:00.000Z","app":"msedge.exe","cat":"focus","idle":true}`,
		// 10:30-11:15 watching
		`{"start":"2026-09-29T02:30:00.000Z","end":"2026-09-29T03:15:00.000Z","app":"msedge.exe","cat":"entertainment","name":"YouTube","idle":false}`,
		// an old span with no category counts as other
		`{"start":"2026-09-29T04:00:00.000Z","end":"2026-09-29T04:10:00.000Z","app":"explorer.exe","idle":false}`,
		`not json`,
	}
	os.WriteFile(filepath.Join(dir, "2026-09-29.jsonl"), []byte(strings.Join(lines, "\n")+"\n"), 0o600)

	got := SummarizeDay(dir, time.Date(2026, 9, 29, 12, 0, 0, 0, loc), loc)
	want := [][3]any{{0, 20, "focus"}, {540, 600, "focus"}, {630, 675, "entertainment"}, {720, 730, "other"}}
	names := map[string]bool{}
	for _, a := range got.Apps {
		names[a[0].(string)] = true
	}
	for _, n := range []string{"VS Code", "Other websites", "YouTube", "File Explorer"} {
		if !names[n] {
			t.Errorf("missing %s in %v", n, got.Apps)
		}
	}
	if got.Day != "2026-09-29" || len(got.Spans) != len(want) {
		t.Fatalf("got %+v", got)
	}
	for i := range want {
		if got.Spans[i] != want[i] {
			t.Errorf("span %d: got %v want %v", i, got.Spans[i], want[i])
		}
	}
	// apps: VS Code 30m (the midnight part is yesterday's file clipped to 20m + 30m today),
	// Edge 29m of focus, a named site, and File Explorer 10m
	if len(got.Apps) == 0 || got.Apps[0][0] != "VS Code" {
		t.Errorf("apps: %v", got.Apps)
	}
	if !strings.Contains(string(SummaryLines(dir, time.Date(2026, 9, 29, 12, 0, 0, 0, loc))), `"day":"2026-09-28"`) {
		t.Error("yesterday's summary should ride along too")
	}
}
