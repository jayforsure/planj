//go:build windows

package main

import (
	"path/filepath"
	"strings"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	user32               = windows.NewLazySystemDLL("user32.dll")
	kernel32             = windows.NewLazySystemDLL("kernel32.dll")
	procGetLastInputInfo = user32.NewProc("GetLastInputInfo")
	procEnumChildWindows = user32.NewProc("EnumChildWindows")
	procMessageBoxW      = user32.NewProc("MessageBoxW")
	procGetTickCount     = kernel32.NewProc("GetTickCount")
)

func windowPID(hwnd windows.HWND) uint32 {
	var pid uint32
	windows.GetWindowThreadProcessId(hwnd, &pid)
	return pid
}

func processName(pid uint32) string {
	h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return ""
	}
	defer windows.CloseHandle(h)
	buf := make([]uint16, windows.MAX_LONG_PATH)
	n := uint32(len(buf))
	if err := windows.QueryFullProcessImageName(h, 0, &buf[0], &n); err != nil {
		return ""
	}
	return filepath.Base(windows.UTF16ToString(buf[:n]))
}

// Go never frees callbacks and caps how many can exist, so one is created up front and reused.
var (
	enumHostPID uint32
	enumFound   string
	enumChild   = windows.NewCallback(func(h windows.HWND, _ uintptr) uintptr {
		if pid := windowPID(h); pid != enumHostPID {
			if name := processName(pid); name != "" {
				enumFound = name
				return 0
			}
		}
		return 1
	})
)

func foregroundApp() string {
	hwnd := windows.GetForegroundWindow()
	if hwnd == 0 {
		return "(none)"
	}
	pid := windowPID(hwnd)
	name := processName(pid)
	if strings.EqualFold(name, "ApplicationFrameHost.exe") {
		// Store (UWP) apps run inside a frame window; the real app owns a child window.
		enumHostPID, enumFound = pid, ""
		procEnumChildWindows.Call(uintptr(hwnd), enumChild, 0)
		if enumFound != "" {
			return enumFound
		}
	}
	if name == "" {
		return "(unknown)"
	}
	return name
}

type lastInputInfo struct {
	cbSize uint32
	dwTime uint32
}

func idleDuration() time.Duration {
	lii := lastInputInfo{cbSize: uint32(unsafe.Sizeof(lastInputInfo{}))}
	if r, _, _ := procGetLastInputInfo.Call(uintptr(unsafe.Pointer(&lii))); r == 0 {
		return 0
	}
	tick, _, _ := procGetTickCount.Call()
	// Both are 32-bit millisecond tick counts; unsigned subtraction survives the 49-day wraparound.
	return time.Duration(uint32(tick)-lii.dwTime) * time.Millisecond
}

func messageBox(text string) {
	title, _ := windows.UTF16PtrFromString("planj")
	msg, _ := windows.UTF16PtrFromString(text)
	const mbIconInformation = 0x40
	procMessageBoxW.Call(0, uintptr(unsafe.Pointer(msg)), uintptr(unsafe.Pointer(title)), mbIconInformation)
}
