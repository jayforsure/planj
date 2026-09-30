package main

import (
	"bufio"
	"os"
	"strings"
	"time"
)

// Categories a foreground window can be sorted into. The window title is read only to
// decide the category and is never written anywhere.
const (
	CatFocus         = "focus"
	CatEntertainment = "entertainment"
	CatSocial        = "social"
	CatChat          = "chat"
	CatOther         = "other"
)

// Rule maps a lowercase substring of "<app> <title>" to a category, and names what matched
// ("YouTube") so the day can list it. The name comes from the rule, never from the title.
type Rule struct {
	Needle string
	Cat    string
	Name   string
}

// builtinRules are checked after the user's own rules; first match wins. Order matters:
// "youtube" beats a generic browser, and chat apps beat the "focus" editors below them.
var builtinRules = []Rule{
	// entertainment: watching, listening, playing
	{"youtube", CatEntertainment, "YouTube"}, {"netflix", CatEntertainment, "Netflix"}, {"twitch", CatEntertainment, "Twitch"},
	{"bilibili", CatEntertainment, "Bilibili"}, {"disney+", CatEntertainment, "Disney+"}, {"prime video", CatEntertainment, "Prime Video"},
	{"iqiyi", CatEntertainment, "iQIYI"}, {"viu", CatEntertainment, "Viu"}, {"crunchyroll", CatEntertainment, "Crunchyroll"},
	{"spotify", CatEntertainment, "Spotify"}, {"vlc", CatEntertainment, "VLC"}, {"potplayer", CatEntertainment, "PotPlayer"},
	{"steam", CatEntertainment, "Steam"}, {"riotclient", CatEntertainment, "Riot Client"}, {"leagueclient", CatEntertainment, "League of Legends"},
	{"league of legends", CatEntertainment, "League of Legends"}, {"valorant", CatEntertainment, "Valorant"}, {"genshin", CatEntertainment, "Genshin Impact"},
	// social feeds
	{"instagram", CatSocial, "Instagram"}, {"facebook", CatSocial, "Facebook"}, {"reddit", CatSocial, "Reddit"}, {"tiktok", CatSocial, "TikTok"},
	{"twitter", CatSocial, "X"}, {" / x ", CatSocial, "X"}, {"threads", CatSocial, "Threads"}, {"xiaohongshu", CatSocial, "RedNote"},
	{"rednote", CatSocial, "RedNote"}, {"linkedin", CatSocial, "LinkedIn"},
	// chat
	{"whatsapp", CatChat, "WhatsApp"}, {"telegram", CatChat, "Telegram"}, {"discord", CatChat, "Discord"}, {"wechat", CatChat, "WeChat"},
	{"messenger", CatChat, "Messenger"}, {"slack", CatChat, "Slack"},
	// Google and Microsoft services, meetings, shopping: specific names before generic words
	{"gmail", CatChat, "Gmail"}, {"outlook", CatChat, "Outlook"}, {"microsoft teams", CatChat, "Teams"},
	{"google meet", CatChat, "Google Meet"}, {"zoom workplace", CatChat, "Zoom"}, {"zoom meeting", CatChat, "Zoom"},
	{"google docs", CatFocus, "Google Docs"}, {"google sheets", CatFocus, "Google Sheets"},
	{"google slides", CatFocus, "Google Slides"}, {"google drive", CatFocus, "Google Drive"},
	{"google calendar", CatFocus, "Google Calendar"}, {"google classroom", CatFocus, "Google Classroom"},
	{"gemini", CatFocus, "Gemini"}, {"perplexity", CatFocus, "Perplexity"}, {"canva", CatFocus, "Canva"},
	{"figma", CatFocus, "Figma"}, {"wikipedia", CatFocus, "Wikipedia"}, {"google search", CatOther, "Google Search"},
	{"shopee", CatOther, "Shopee"}, {"lazada", CatOther, "Lazada"}, {"medium", CatOther, "Medium"},
	// focus: building, writing, studying
	{"code.exe", CatFocus, "VS Code"}, {"windowsterminal", CatFocus, "Terminal"}, {"pycharm", CatFocus, "PyCharm"}, {"idea64", CatFocus, "IntelliJ IDEA"},
	{"android studio", CatFocus, "Android Studio"}, {"studio64", CatFocus, "Android Studio"}, {"winword", CatFocus, "Word"}, {"excel", CatFocus, "Excel"},
	{"powerpnt", CatFocus, "PowerPoint"}, {"acrobat", CatFocus, "Acrobat"}, {".pdf", CatFocus, "PDF reading"}, {"obsidian", CatFocus, "Obsidian"},
	{"notion", CatFocus, "Notion"}, {"github", CatFocus, "GitHub"}, {"gitlab", CatFocus, "GitLab"}, {"stack overflow", CatFocus, "Stack Overflow"},
	{"leetcode", CatFocus, "LeetCode"}, {"overleaf", CatFocus, "Overleaf"}, {"coursera", CatFocus, "Coursera"}, {"udemy", CatFocus, "Udemy"},
	{"moodle", CatFocus, "Moodle"}, {"lecture", CatFocus, "Lectures"}, {"tutorial", CatFocus, "Tutorials"}, {"documentation", CatFocus, "Documentation"},
	{" docs", CatFocus, "Docs"}, {"claude", CatFocus, "Claude"}, {"chatgpt", CatFocus, "ChatGPT"}, {"railway", CatFocus, "Railway"},
}

// Classify sorts a foreground window. User rules come first so a person can say that their
// university portal is focus, or that one YouTube channel they learn from is focus.
func Classify(app, title string, user []Rule) string {
	cat, _ := ClassifyNamed(app, title, user)
	return cat
}

// ClassifyNamed also returns what matched ("YouTube", "VS Code"), or "" when nothing did.
func ClassifyNamed(app, title string, user []Rule) (string, string) {
	return ClassifyWithSite(app, title, "", user)
}

// ClassifyWithSite also looks at the site open in a browser ("youtube.com"), so rules can
// match it, and names an unlisted page by its site instead of "Other websites".
func ClassifyWithSite(app, title, site string, user []Rule) (string, string) {
	hay := " " + strings.ToLower(app+" "+title+" "+site) + " "
	for _, set := range [][]Rule{user, builtinRules} {
		for _, r := range set {
			if strings.Contains(hay, r.Needle) {
				return r.Cat, r.Name
			}
		}
	}
	if IsBrowser(app) {
		// A page we don't recognise: count the time under its site, never the page itself.
		if site != "" {
			return CatOther, site
		}
		return CatOther, "Other websites"
	}
	return CatOther, ""
}

// IsBrowser says whether a program is a web browser, whose own name says nothing about use.
func IsBrowser(app string) bool {
	switch strings.ToLower(app) {
	case "msedge.exe", "chrome.exe", "firefox.exe", "brave.exe", "opera.exe", "vivaldi.exe", "arc.exe":
		return true
	}
	return false
}

// AppName turns a program file into a readable name: "msedge.exe" -> "Edge". A short list of
// well-known programs is named by hand; any other takes the name it gave when first seen.
func AppName(exe string) string {
	known := map[string]string{
		"msedge.exe": "Edge", "chrome.exe": "Chrome", "firefox.exe": "Firefox", "brave.exe": "Brave",
		"code.exe": "VS Code", "notepad.exe": "Notepad", "explorer.exe": "File Explorer",
		"windowsterminal.exe": "Terminal", "notion.exe": "Notion",
		"(none)": "Desktop", "(unknown)": "Other", "systemsettings.exe": "Settings",
		"snippingtool.exe": "Snipping Tool", "planj-tracker.exe": "planj",
	}
	if n, ok := known[strings.ToLower(exe)]; ok {
		return n
	}
	if windowsScreens[strings.ToLower(exe)] {
		return "Windows"
	}
	if n, ok := programs.Name(exe); ok { // what the program said about itself when first seen
		return n
	}
	return strings.TrimSuffix(strings.TrimSuffix(exe, ".exe"), ".EXE")
}

// IdleAllowance is how long without input still counts as present. Watching a video or
// reading a PDF involves no typing, so they get longer than the default.
func IdleAllowance(cat, app, title string, def time.Duration) time.Duration {
	hay := strings.ToLower(app + " " + title)
	switch {
	case cat == CatEntertainment:
		return 30 * time.Minute
	case strings.Contains(hay, ".pdf") || strings.Contains(hay, "acrobat") || strings.Contains(hay, "reader"):
		return 10 * time.Minute
	}
	return def
}

// LoadRules reads the person's own rules, one per line: "focus: university portal".
// Lines that do not parse are skipped; a missing file means no rules.
func LoadRules(path string) []Rule {
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []Rule
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		cat, needle, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		cat, needle = strings.ToLower(strings.TrimSpace(cat)), strings.ToLower(strings.TrimSpace(needle))
		switch cat {
		case CatFocus, CatEntertainment, CatSocial, CatChat, CatOther:
			if needle != "" {
				out = append(out, Rule{Needle: needle, Cat: cat, Name: strings.TrimSpace(strings.SplitN(line, ":", 2)[1])})
			}
		}
	}
	return out
}
