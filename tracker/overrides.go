package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

// CategoryOverrides are the person's own choices of what a program or site counts as, made
// on the phone ("lms.utar.edu.my counts as focus"). They beat every rule, and apply to past
// records as well as new ones, so a forecast built on history is corrected too.
type CategoryOverrides struct {
	mu   sync.Mutex
	path string
	m    map[string]string // display name -> category
}

var overrides = &CategoryOverrides{m: map[string]string{}}

func validCat(c string) bool {
	switch c {
	case CatFocus, CatEntertainment, CatSocial, CatChat, CatOther:
		return true
	}
	return false
}

// LoadOverrides reads the choices from dir; a missing or damaged file means none.
func LoadOverrides(dir string) {
	overrides.mu.Lock()
	defer overrides.mu.Unlock()
	overrides.path = filepath.Join(dir, "name_categories.json")
	overrides.m = map[string]string{}
	if b, err := os.ReadFile(overrides.path); err == nil {
		json.Unmarshal(b, &overrides.m)
	}
}

func (o *CategoryOverrides) Get(name string) (string, bool) {
	o.mu.Lock()
	defer o.mu.Unlock()
	c, ok := o.m[name]
	return c, ok
}

// Set records a choice and saves it; it says whether anything changed.
func (o *CategoryOverrides) Set(name, cat string) bool {
	if name == "" || !validCat(cat) {
		return false
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.m[name] == cat {
		return false
	}
	o.m[name] = cat
	if o.path != "" {
		if b, err := json.MarshalIndent(o.m, "", "  "); err == nil {
			tmp := o.path + ".tmp"
			if os.WriteFile(tmp, b, 0o600) == nil {
				os.Rename(tmp, o.path)
			}
		}
	}
	return true
}

// ApplyOverride returns the person's choice for this name, else the category as classified.
func ApplyOverride(name, cat string) string {
	if c, ok := overrides.Get(name); ok {
		return c
	}
	return cat
}

// TakeRules pulls the phone's {"event":"pc_rule","name":…,"cat":…} lines out of an upload,
// applies them, and returns the rest of the upload plus whether any choice changed.
func TakeRules(jsonl []byte) ([]byte, bool) {
	var rest bytes.Buffer
	changed := false
	sc := bufio.NewScanner(bytes.NewReader(jsonl))
	sc.Buffer(make([]byte, 64*1024), 4*1024*1024)
	for sc.Scan() {
		line := sc.Bytes()
		if bytes.Contains(line, []byte(`"pc_rule"`)) {
			var r struct {
				Event, Name, Cat string
			}
			if json.Unmarshal(line, &r) == nil && r.Event == "pc_rule" {
				if overrides.Set(r.Name, r.Cat) {
					changed = true
				}
				continue
			}
		}
		rest.Write(line)
		rest.WriteByte('\n')
	}
	return rest.Bytes(), changed
}
