package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"time"
)

const tsLayout = "2006-01-02T15:04:05.000Z07:00"

type record struct {
	Start string `json:"start"`
	End   string `json:"end"`
	App   string `json:"app"`
	Idle  bool   `json:"idle"`
}

// Writer appends spans as JSON lines to one file per local day.
type Writer struct {
	Dir string
	Loc *time.Location
}

func (w Writer) Write(spans []Span) error {
	for _, s := range spans {
		line, err := json.Marshal(record{
			Start: s.Start.UTC().Format(tsLayout),
			End:   s.End.UTC().Format(tsLayout),
			App:   s.App,
			Idle:  s.Idle,
		})
		if err != nil {
			return err
		}
		path := filepath.Join(w.Dir, s.Start.In(w.Loc).Format("2006-01-02")+".jsonl")
		f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
		if err != nil {
			return err
		}
		_, err = f.Write(append(line, '\n'))
		if cerr := f.Close(); err == nil {
			err = cerr
		}
		if err != nil {
			return err
		}
	}
	return nil
}
