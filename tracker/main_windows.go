//go:build windows

package main

import (
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const (
	appName   = "planj-tracker"
	exeName   = appName + ".exe"
	mutexName = `Local\` + appName
	runKey    = `Software\Microsoft\Windows\CurrentVersion\Run`
	pollEvery = 5 * time.Second
)

func main() {
	root, err := os.UserCacheDir() // %LOCALAPPDATA% on Windows
	if err != nil {
		messageBox("Cannot find the local app data folder: " + err.Error())
		os.Exit(1)
	}
	root = filepath.Join(root, "planj")

	cmd := ""
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	switch cmd {
	case "":
		switch {
		case trackerRunning():
			// Opening it again once it is already tracking is how people get their phone pairing code.
			showPairing(root)
		case !isInstalledCopy(root):
			installWithMessage(root)
		default:
			run(root) // started at sign-in
		}
	case "pair":
		showPairing(root)
	case "install":
		installWithMessage(root)
	case "uninstall":
		if err := uninstall(); err != nil {
			messageBox("Uninstall failed: " + err.Error())
			os.Exit(1)
		}
		messageBox("planj tracker is stopped and will no longer start with Windows.\nYour recorded data was kept in:\n" + filepath.Join(root, "activity"))
	default:
		messageBox("Unknown command: " + os.Args[1] + "\nUse: install | pair | uninstall")
		os.Exit(2)
	}
}

func isInstalledCopy(root string) bool {
	self, err := os.Executable()
	return err == nil && strings.EqualFold(filepath.Clean(self), filepath.Join(root, exeName))
}

func trackerRunning() bool {
	name, _ := windows.UTF16PtrFromString(mutexName)
	h, err := windows.OpenMutex(windows.SYNCHRONIZE, false, name)
	if err != nil {
		return false
	}
	windows.CloseHandle(h)
	return true
}

func installWithMessage(root string) {
	if trackerRunning() {
		messageBox("planj tracker is already installed and running.")
		return
	}
	if err := install(root); err != nil {
		messageBox("Install failed: " + err.Error())
		os.Exit(1)
	}
	messageBox("planj tracker is installed and running.\nIt will start automatically when you sign in to Windows.\n\nIt records only which app is in front and whether you are active — never window titles, websites or what you type.\n\nTo sync your phone, open this file again to see your pairing code.\n\nData folder:\n" + filepath.Join(root, "activity"))
}

func run(root string) {
	dir := filepath.Join(root, "activity")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		messageBox("Cannot create data folder: " + err.Error())
		os.Exit(1)
	}
	if lf, err := os.OpenFile(filepath.Join(root, "tracker.log"), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600); err == nil {
		log.SetOutput(lf)
		defer lf.Close()
	}

	name, _ := windows.UTF16PtrFromString(mutexName)
	mutex, err := windows.CreateMutex(nil, false, name)
	if errors.Is(err, windows.ERROR_ALREADY_EXISTS) {
		return
	}
	if err != nil {
		log.Printf("mutex: %v", err)
		return
	}
	defer windows.CloseHandle(mutex)
	log.Printf("started, writing to %s", dir)
	go pullLoop(root)

	rec := &Recorder{IdleAfter: 3 * time.Minute, MaxSpan: time.Minute, MaxGap: 30 * time.Second}
	w := Writer{Dir: dir, Loc: time.Local}
	observe := func(now time.Time) {
		// Round(0) drops the monotonic reading, which can pause during sleep and hide the gap.
		if err := w.Write(rec.Observe(now.Round(0), foregroundApp(), idleDuration())); err != nil {
			log.Printf("write: %v", err)
		}
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	ticker := time.NewTicker(pollEvery)
	defer ticker.Stop()

	observe(time.Now())
	for {
		select {
		case now := <-ticker.C:
			observe(now)
		case <-stop:
			if err := w.Write(rec.Flush()); err != nil {
				log.Printf("write on stop: %v", err)
			}
			log.Print("stopped")
			return
		}
	}
}

func install(root string) error {
	if err := os.MkdirAll(root, 0o700); err != nil {
		return err
	}
	self, err := os.Executable()
	if err != nil {
		return err
	}
	target := filepath.Join(root, exeName)
	if !isInstalledCopy(root) {
		if err := copyFile(self, target); err != nil {
			return fmt.Errorf("copying to %s (if the tracker is already running, uninstall first): %w", target, err)
		}
	}
	k, _, err := registry.CreateKey(registry.CURRENT_USER, runKey, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	if err := k.SetStringValue(appName, `"`+target+`"`); err != nil {
		return err
	}
	return exec.Command(target).Start()
}

func uninstall() error {
	k, err := registry.OpenKey(registry.CURRENT_USER, runKey, registry.SET_VALUE)
	if err == nil {
		err = k.DeleteValue(appName)
		k.Close()
	}
	if err != nil && !errors.Is(err, registry.ErrNotExist) {
		return err
	}
	// Stop running copies, but not this uninstall process itself.
	kill := exec.Command("taskkill", "/F", "/IM", exeName, "/FI", fmt.Sprintf("PID ne %d", os.Getpid()))
	kill.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	_ = kill.Run() // exits non-zero when nothing was running
	return nil
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}
