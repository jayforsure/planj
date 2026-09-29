package main

import "testing"

func TestSiteKeepsOnlyTheSite(t *testing.T) {
	cases := map[string]string{
		"https://www.youtube.com/watch?v=abc&t=10": "youtube.com",
		"youtube.com/watch?v=abc":                  "youtube.com",
		"lms.utar.edu.my/course/view.php?id=9":     "lms.utar.edu.my",
		"http://user:secret@example.org:8080/x":    "example.org",
		"HTTPS://Docs.Google.com/document/d/123":   "docs.google.com",
		"edge://settings":                          "",
		"about:blank":                              "",
		"file:///C:/notes.txt":                     "",
		"how to cook rice":                         "",
		"localhost:3000":                           "",
		"":                                         "",
	}
	for in, want := range cases {
		if got := SiteFromAddress(in); got != want {
			t.Errorf("%q: got %q, want %q", in, got, want)
		}
	}
}

func TestSiteNamesUnlistedPagesAndRulesStillWin(t *testing.T) {
	if cat, name := ClassifyWithSite("msedge.exe", "Course page - Microsoft Edge", "lms.utar.edu.my", nil); cat != CatOther || name != "lms.utar.edu.my" {
		t.Fatalf("unlisted site: %s %q", cat, name)
	}
	if cat, name := ClassifyWithSite("msedge.exe", "Some video - Microsoft Edge", "youtube.com", nil); cat != CatEntertainment || name != "YouTube" {
		t.Fatalf("site alone should find YouTube: %s %q", cat, name)
	}
	rules := []Rule{{Needle: "utar.edu.my", Cat: CatFocus, Name: "UTAR"}}
	if cat, name := ClassifyWithSite("msedge.exe", "Course page - Microsoft Edge", "lms.utar.edu.my", rules); cat != CatFocus || name != "UTAR" {
		t.Fatalf("user rule on a site: %s %q", cat, name)
	}
	if _, name := ClassifyWithSite("msedge.exe", "Page - Microsoft Edge", "", nil); name != "Other websites" {
		t.Fatalf("no site read: %q", name)
	}
	if !IsPrivateWindow("New tab - [InPrivate] - Microsoft Edge") || IsPrivateWindow("YouTube - Microsoft Edge") {
		t.Fatal("private windows")
	}
}
