package main

import "time"

// Span is a continuous stretch of time with the same foreground app and idle state.
type Span struct {
	Start time.Time
	End   time.Time
	App   string
	Idle  bool
}

// Recorder turns periodic observations into closed spans.
type Recorder struct {
	IdleAfter time.Duration // no input for this long counts as idle
	MaxSpan   time.Duration // long spans are split so a crash loses at most this much
	MaxGap    time.Duration // observations further apart than this mean the machine slept
	cur       *Span
}

// Observe records the foreground app at t, given how long there has been no input.
// It returns the spans that are finished and ready to persist.
func (r *Recorder) Observe(t time.Time, app string, idleFor time.Duration) []Span {
	idle := idleFor >= r.IdleAfter
	if r.cur == nil {
		r.cur = &Span{Start: t, End: t, App: app, Idle: idle}
		return nil
	}

	var out []Span
	switch {
	case t.Sub(r.cur.End) > r.MaxGap:
		// Don't stretch the last span across sleep or hibernation.
		out = r.emit(out, r.cur.End)
		r.cur = &Span{Start: t, End: t, App: app, Idle: idle}
	case idle != r.cur.Idle:
		// The state really changed at the last input, not at this poll.
		boundary := t.Add(-idleFor)
		if boundary.Before(r.cur.Start) {
			boundary = r.cur.Start
		}
		out = r.emit(out, boundary)
		r.cur = &Span{Start: boundary, End: t, App: app, Idle: idle}
	case app != r.cur.App:
		out = r.emit(out, t)
		r.cur = &Span{Start: t, End: t, App: app, Idle: idle}
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
				r.cur = &Span{Start: split, End: t, App: app, Idle: idle}
			}
		}
	}
	return out
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
