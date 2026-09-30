package main

import "time"

// Span is a continuous stretch of time with the same foreground app, category and idle state.
type Span struct {
	Start time.Time
	End   time.Time
	App   string
	Cat   string
	Name  string // what the category rule matched, e.g. "YouTube"; never the window title
	Idle  bool
}

// Recorder turns periodic observations into closed spans.
type Recorder struct {
	IdleAfter time.Duration // no input for this long counts as idle, unless the observation says otherwise
	MaxSpan   time.Duration // long spans are split so a crash loses at most this much
	MaxGap    time.Duration // observations further apart than this mean the machine slept
	cur       *Span
}

// Observation is one poll: what is in front, and how long since the last input.
type Observation struct {
	App       string
	Cat       string
	Name      string
	IdleFor   time.Duration
	IdleAfter time.Duration // this window's allowance; zero means the recorder's default
}

// Observe records the foreground app at t, given how long there has been no input.
// It returns the spans that are finished and ready to persist.
func (r *Recorder) Observe(t time.Time, app string, idleFor time.Duration) []Span {
	return r.ObserveFull(t, Observation{App: app, IdleFor: idleFor})
}

// ObserveFull is Observe with a category and a per-window idle allowance.
func (r *Recorder) ObserveFull(t time.Time, o Observation) []Span {
	after := o.IdleAfter
	if after == 0 {
		after = r.IdleAfter
	}
	idle := o.IdleFor >= after
	app, cat, name, idleFor := o.App, o.Cat, o.Name, o.IdleFor
	if r.cur == nil {
		r.cur = &Span{Start: t, End: t, App: app, Cat: cat, Name: name, Idle: idle}
		return nil
	}

	var out []Span
	switch {
	case t.Sub(r.cur.End) > r.MaxGap:
		// Don't stretch the last span across sleep or hibernation.
		out = r.emit(out, r.cur.End)
		r.cur = &Span{Start: t, End: t, App: app, Cat: cat, Name: name, Idle: idle}
	case idle != r.cur.Idle:
		// The state really changed at the last input, not at this poll.
		boundary := t.Add(-idleFor)
		if boundary.Before(r.cur.Start) {
			boundary = r.cur.Start
		}
		out = r.emit(out, boundary)
		r.cur = &Span{Start: boundary, End: t, App: app, Cat: cat, Name: name, Idle: idle}
	case app != r.cur.App || cat != r.cur.Cat || name != r.cur.Name:
		out = r.emit(out, t)
		r.cur = &Span{Start: t, End: t, App: app, Cat: cat, Name: name, Idle: idle}
	default:
		r.cur.End = t
		if r.cur.End.Sub(r.cur.Start) >= r.MaxSpan {
			split := t
			if !idle {
				// Time since the last input may still turn out to be idle, so don't commit it yet.
				split = t.Add(-idleFor)
			}
			if split.After(r.cur.Start) {
				out = r.emit(out, split)
				r.cur = &Span{Start: split, End: t, App: app, Cat: cat, Name: name, Idle: idle}
			}
		}
	}
	return out
}

// Current is the span in progress, not yet written, as it stands at its last observation.
func (r *Recorder) Current() (Span, bool) {
	if r.cur == nil {
		return Span{}, false
	}
	return *r.cur, true
}

// Flush closes the span in progress, e.g. on shutdown.
func (r *Recorder) Flush() []Span {
	if r.cur == nil {
		return nil
	}
	out := r.emit(nil, r.cur.End)
	r.cur = nil
	return out
}

func (r *Recorder) emit(out []Span, end time.Time) []Span {
	s := *r.cur
	s.End = end
	if s.End.After(s.Start) {
		out = append(out, s)
	}
	return out
}
