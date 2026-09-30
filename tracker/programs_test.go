package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestFriendlyNameFromWhatProgramsSayAboutThemselves(t *testing.T) {
	cases := []struct{ exe, product, desc, want string }{
		{"RCClient.exe", "AnyViewer", "AnyViewer RCClient", "AnyViewer"},
		{"msrdc.exe", "Microsoft® Remote Desktop", "Remote Desktop", "Remote Desktop"},
		{"urban-vpn-app.exe", "UrbanVPN", "UrbanVPN for Windows", "UrbanVPN"},
		{"Notepad.exe", "Microsoft® Windows® Operating System", "Notepad", "Notepad"},
		{"Discord.exe", "Discord", "Discord", "Discord"},
		{"LockApp.exe", "Microsoft® Windows® Operating System", "LockApp.exe", "Windows"},
		{"PickerHost.exe", "Microsoft® Windows® Operating System", "File Picker UI Host", "Windows"},
		{"SnippingTool.exe", "", "", "SnippingTool"},
		{"tool.exe", "Some Very Long Product Name That Goes On And On", "", "tool"},
		{"game.exe", "Acme Launcher", "Plays games", "Acme Launcher"},
	}
	for _, c := range cases {
		if got := FriendlyName(c.exe, c.product, c.desc); got != c.want {
			t.Errorf("%s: got %q, want %q", c.exe, got, c.want)
		}
	}
}

func TestProgramBookNamesOldSpansToo(t *testing.T) {
	dir := t.TempDir()
	LoadPrograms(dir)
	defer func() { programs = &ProgramBook{names: map[string]string{}} }()
	if AppName("RCClient.exe") != "RCClient" {
		t.Fatal("unknown before it is seen")
	}
	if !programs.Learn("RCClient.exe", "AnyViewer") || programs.Learn("rcclient.exe", "AnyViewer") {
		t.Fatal("new once, then known")
	}
	if AppName("RCClient.exe") != "AnyViewer" || DisplayName("RCClient.exe", "") != "AnyViewer" {
		t.Fatalf("named from the book: %q", AppName("RCClient.exe"))
	}
	// survives a restart
	programs = &ProgramBook{names: map[string]string{}}
	LoadPrograms(dir)
	if AppName("RCClient.exe") != "AnyViewer" {
		t.Fatal("book not saved")
	}
	if _, err := os.Stat(filepath.Join(dir, "programs.json")); err != nil {
		t.Fatal(err)
	}
	if AppName("SearchHost.exe") != "Windows" || AppName("SystemSettings.exe") != "Settings" {
		t.Fatal("Windows' own screens")
	}
}
