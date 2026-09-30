package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// windowsScreens are Windows' own screens (Start, search, pickers, hosts): one row, "Windows",
// rather than a dozen internal names nobody recognises.
var windowsScreens = map[string]bool{
	"searchhost.exe": true, "startmenuexperiencehost.exe": true, "shellhost.exe": true,
	"shellexperiencehost.exe": true, "pickerhost.exe": true, "openwith.exe": true,
	"rundll32.exe": true, "applicationframehost.exe": true, "textinputhost.exe": true,
	"lockapp.exe": true, "dwm.exe": true, "consent.exe": true,
}

// FriendlyName picks how a program is listed from what it says about itself: its product
// name or its description, whichever is the plainer (AnyViewer, not "AnyViewer RCClient";
// Remote Desktop, not "Microsoft Remote Desktop"). Windows' own programs all call their
// product "Microsoft Windows Operating System", so for those the description is used.
func FriendlyName(exe, product, description string) string {
	if windowsScreens[strings.ToLower(exe)] {
		return "Windows"
	}
	product, description = clean(product), clean(description)
	base := strings.TrimSuffix(strings.TrimSuffix(filepath.Base(exe), ".exe"), ".EXE")
	if strings.Contains(strings.ToLower(product), "operating system") {
		product = ""
	}
	if strings.EqualFold(description, base) || strings.EqualFold(description, filepath.Base(exe)) {
		description = "" // says nothing the file name does not
	}
	pick := product
	switch {
	case product == "":
		pick = description
	case description == "":
	case strings.Contains(strings.ToLower(description), strings.ToLower(product)):
		pick = product // "AnyViewer RCClient" has the product in it: the product is the name
	case strings.Contains(strings.ToLower(product), strings.ToLower(description)):
		pick = description // "Microsoft Remote Desktop" around "Remote Desktop": the shorter
	}
	if pick == "" || len([]rune(pick)) > 32 {
		return base
	}
	return pick
}

func clean(s string) string {
	s = strings.NewReplacer("®", "", "™", "", "©", "", "(R)", "", "(TM)", "").Replace(s)
	return strings.Join(strings.Fields(s), " ")
}

// ProgramBook remembers each program's name once it has been worked out, on disk, so that
// days recorded before (and spans with no name) are listed the same way.
type ProgramBook struct {
	mu    sync.Mutex
	path  string
	names map[string]string // lower-case exe -> name
}

var programs = &ProgramBook{names: map[string]string{}}

// LoadPrograms reads the book from dir; a missing or damaged file just starts empty.
func LoadPrograms(dir string) {
	programs.mu.Lock()
	defer programs.mu.Unlock()
	programs.path = filepath.Join(dir, "programs.json")
	if b, err := os.ReadFile(programs.path); err == nil {
		json.Unmarshal(b, &programs.names)
	}
	if programs.names == nil {
		programs.names = map[string]string{}
	}
}

func (b *ProgramBook) has(exe string) bool {
	_, ok := b.Name(exe)
	return ok
}

func baseName(exe string) string {
	return strings.TrimSuffix(strings.TrimSuffix(filepath.Base(exe), ".exe"), ".EXE")
}

func (b *ProgramBook) Name(exe string) (string, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	n, ok := b.names[strings.ToLower(exe)]
	return n, ok
}

// Learn records a program's name and saves the book; it says whether the program was new.
func (b *ProgramBook) Learn(exe, name string) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	key := strings.ToLower(exe)
	if b.names[key] == name {
		return false
	}
	_, known := b.names[key]
	b.names[key] = name
	if b.path != "" {
		if data, err := json.MarshalIndent(b.names, "", "  "); err == nil {
			tmp := b.path + ".tmp"
			if os.WriteFile(tmp, data, 0o600) == nil {
				os.Rename(tmp, b.path)
			}
		}
	}
	return !known
}
