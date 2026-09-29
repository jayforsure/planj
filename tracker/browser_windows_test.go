//go:build windows

package main

import (
	"os"
	"runtime"
	"strings"
	"testing"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Reads the site from every open browser window. Needs a real desktop, so it only runs
// when PLANJ_LIVE_SITES=1; it prints sites only, never titles or addresses.
func TestLiveSitesFromOpenBrowsers(t *testing.T) {
	if os.Getenv("PLANJ_LIVE_SITES") != "1" {
		t.Skip("set PLANJ_LIVE_SITES=1 on a Windows desktop")
	}
	runtime.LockOSThread()
	sr := newSiteReader()
	if sr == nil {
		t.Fatal("UI Automation unavailable")
	}
	var wins []windows.HWND
	cb := windows.NewCallback(func(h windows.HWND, _ uintptr) uintptr {
		if IsBrowser(processName(windowPID(h))) && windows.IsWindowVisible(h) {
			wins = append(wins, h)
		}
		return 1
	})
	procEnumWindows := user32.NewProc("EnumWindows")
	procEnumWindows.Call(cb, 0)
	for _, h := range wins {
		buf := make([]uint16, 512)
		n, _, _ := procGetWindowTextW.Call(uintptr(h), uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
		title := windows.UTF16ToString(buf[:n])
		if strings.TrimSpace(title) == "" {
			continue
		}
		start := time.Now()
		site := sr.Site(h, title)
		t.Logf("%s window: site %q in %v", processName(windowPID(h)), site, time.Since(start).Round(time.Millisecond))
	}
}
