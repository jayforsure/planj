//go:build windows

package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// programSeen is a program that came to the front, with where its file is.
type programSeen struct {
	name, path string
}

// iconLoop sends each program's own icon to the phone once, so the phone can show AnyViewer's
// or Notepad's real icon, not just a letter. Which icons went is kept in icons_sent.json.
func iconLoop(root string, seen <-chan programSeen) {
	file := filepath.Join(root, "icons_sent.json")
	sent := map[string]string{}
	if b, err := os.ReadFile(file); err == nil {
		json.Unmarshal(b, &sent)
	}
	client := &http.Client{Timeout: 30 * time.Second}
	for s := range seen {
		if s.path == "" || sent[s.name] != "" || skipIcon(s.name) {
			continue
		}
		png, err := iconPNG(s.path, 128)
		if err != nil {
			sent[s.name] = "none" // no icon inside; the phone shows its letter
			saveSent(file, sent)
			continue
		}
		p, ok, err := loadPairing(root)
		if err != nil || !ok {
			continue // tried again the next time the program comes to the front
		}
		line, _ := json.Marshal(map[string]string{"event": "pc_icon", "name": s.name, "png": base64.StdEncoding.EncodeToString(png)})
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		err = PostReply(ctx, client, relayURL(root), p, append(line, '\n'))
		cancel()
		if err != nil {
			log.Printf("icon: %v", err)
			continue
		}
		sent[s.name] = time.Now().UTC().Format(time.RFC3339)
		saveSent(file, sent)
	}
}

// Browsers are never the app (their rows are sites), and Windows' own screens share one row.
func skipIcon(name string) bool {
	switch name {
	case "Windows", "Desktop", "Other", "Other websites", "Edge", "Chrome", "Firefox", "Brave":
		return true
	}
	return false
}

func saveSent(file string, sent map[string]string) {
	if b, err := json.MarshalIndent(sent, "", "  "); err == nil {
		os.WriteFile(file, b, 0o600)
	}
}

// offerProgram hands a program to the icon loop without ever blocking the tracking loop.
func offerProgram(ch chan programSeen, s programSeen) {
	select {
	case ch <- s:
	default:
	}
}

// warmUp names the programs used in the last week that are running now, and queues their
// icons, so the phone gets them without waiting for each to come to the front again.
func warmUp(dir string, ch chan programSeen) {
	used := map[string]bool{}
	for d := 0; d < 7; d++ {
		f, err := os.Open(filepath.Join(dir, time.Now().AddDate(0, 0, -d).Format("2006-01-02")+".jsonl"))
		if err != nil {
			continue
		}
		sc := bufio.NewScanner(f)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		for sc.Scan() {
			var r spanRec
			if json.Unmarshal(sc.Bytes(), &r) == nil && r.App != "" {
				used[strings.ToLower(r.App)] = true
			}
		}
		f.Close()
	}
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return
	}
	defer windows.CloseHandle(snap)
	var e windows.ProcessEntry32
	e.Size = uint32(unsafe.Sizeof(e))
	done := map[string]bool{}
	for err = windows.Process32First(snap, &e); err == nil; err = windows.Process32Next(snap, &e) {
		exe := windows.UTF16ToString(e.ExeFile[:])
		key := strings.ToLower(exe)
		if !used[key] || done[key] || IsBrowser(exe) {
			continue
		}
		path := processImage(e.ProcessID)
		if path == "" {
			continue
		}
		done[key] = true
		name, _ := programName(exe, path)
		offerProgram(ch, programSeen{name: name, path: path})
	}
}
