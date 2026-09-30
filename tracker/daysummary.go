package main

import (
	"bufio"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// DaySummary is what the PC tells the phone about a day: present time by category, in
// minutes since local midnight. No app names, no titles; the phone draws it on its timeline.
type DaySummary struct {
	Event string   `json:"event"` // "pc_day"
	Day   string   `json:"day"`   // local date, 2006-01-02
	Spans [][3]any `json:"spans"` // [startMin, endMin, category]
	Apps  [][3]any `json:"apps"`  // [name, minutes, category], most used first
}

// Screens that are the computer waiting for you, not you using it.
var notUse = map[string]bool{"lockapp.exe": true, "logonui.exe": true, "(none)": true}

type spanRec struct {
	Start string `json:"start"`
	End   string `json:"end"`
	App   string `json:"app"`
	Cat   string `json:"cat"`
	Name  string `json:"name"`
	Idle  bool   `json:"idle"`
}

// SummarizeDay reads one local day's activity file (and the day before, for spans that
// cross midnight) and returns present PC time as merged [start, end, cat] minute ranges.
func SummarizeDay(dir string, day time.Time, loc *time.Location) DaySummary {
	return SummarizeDayWith(dir, day, loc, nil)
}

// SummarizeDayWith also counts spans not yet on disk, such as the one still in progress,
// so a live view is right to the second rather than to the last write.
func SummarizeDayWith(dir string, day time.Time, loc *time.Location, extra []Span) DaySummary {
	start := time.Date(day.Year(), day.Month(), day.Day(), 0, 0, 0, 0, loc)
	end := start.AddDate(0, 0, 1)
	type seg struct {
		s, e int
		cat  string
	}
	var segs []seg
	appMin := map[string]float64{}
	appCat := map[string]string{}
	var recs []spanRec
	for _, d := range []time.Time{start.AddDate(0, 0, -1), start} {
		f, err := os.Open(filepath.Join(dir, d.Format("2006-01-02")+".jsonl"))
		if err != nil {
			continue
		}
		sc := bufio.NewScanner(f)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		for sc.Scan() {
			var r spanRec
			if json.Unmarshal(sc.Bytes(), &r) == nil {
				recs = append(recs, r)
			}
		}
		f.Close()
	}
	for _, x := range extra {
		recs = append(recs, spanRec{Start: x.Start.Format(tsLayout), End: x.End.Format(tsLayout), App: x.App, Cat: x.Cat, Name: x.Name, Idle: x.Idle})
	}
	for _, r := range recs {
		if r.Idle || notUse[strings.ToLower(r.App)] {
			continue
		}
		s, err1 := time.Parse(tsLayout, r.Start)
		e, err2 := time.Parse(tsLayout, r.End)
		if err1 != nil || err2 != nil {
			continue
		}
		if s.Before(start) {
			s = start
		}
		if e.After(end) {
			e = end
		}
		if !e.After(s) {
			continue
		}
		cat := r.Cat
		if cat == "" {
			cat = CatOther
		}
		segs = append(segs, seg{int(s.Sub(start).Minutes()), int(e.Sub(start).Minutes() + 0.999), cat})
		name := DisplayName(r.App, r.Name)
		appMin[name] += e.Sub(s).Minutes()
		if name == "Other websites" {
			appCat[name] = CatOther // an unnamed page never claims to be focus or fun in the list
		} else if _, seen := appCat[name]; !seen || cat != CatOther {
			appCat[name] = cat
		}
	}
	sort.Slice(segs, func(i, j int) bool { return segs[i].s < segs[j].s })
	var merged []seg
	for _, g := range segs {
		// Same category with a gap under two minutes is one stretch.
		if n := len(merged); n > 0 && merged[n-1].cat == g.cat && g.s-merged[n-1].e <= 2 {
			if g.e > merged[n-1].e {
				merged[n-1].e = g.e
			}
			continue
		}
		merged = append(merged, g)
	}
	out := DaySummary{Event: "pc_day", Day: start.Format("2006-01-02"), Spans: [][3]any{}, Apps: [][3]any{}}
	names := make([]string, 0, len(appMin))
	for n := range appMin {
		names = append(names, n)
	}
	sort.Slice(names, func(i, j int) bool { return appMin[names[i]] > appMin[names[j]] })
	for i, n := range names {
		if i == 40 || appMin[n] < 1 {
			break
		}
		out.Apps = append(out.Apps, [3]any{n, int(appMin[n] + 0.5), appCat[n]})
	}
	for _, g := range merged {
		if g.e-g.s >= 1 {
			out.Spans = append(out.Spans, [3]any{g.s, g.e, g.cat})
		}
	}
	return out
}

// DisplayName is how a span is listed: the rule's name ("YouTube", a site), "Other websites"
// for an unnamed browser page, else the program's readable name.
func DisplayName(app, name string) string {
	switch {
	case name != "":
		return name
	case IsBrowser(app):
		return "Other websites" // includes spans recorded before sites were named
	default:
		return AppName(app)
	}
}

// Locked says whether a program is the computer waiting for you rather than you using it.
func Locked(app string) bool {
	return notUse[strings.ToLower(app)]
}

// SummaryLines is today's and yesterday's summary as JSON lines, ready to seal.
func SummaryLines(dir string, now time.Time) []byte {
	var buf []byte
	for _, d := range []time.Time{now.AddDate(0, 0, -1), now} {
		line, err := json.Marshal(SummarizeDay(dir, d, now.Location()))
		if err != nil {
			continue
		}
		buf = append(buf, line...)
		buf = append(buf, '\n')
	}
	return buf
}
