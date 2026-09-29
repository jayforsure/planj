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

// Rule maps a lowercase substring of "<app> <title>" to a category.
type Rule struct {
	Needle string
	Cat    string
}

// builtinRules are checked after the user's own rules; first match wins. Order matters:
// "youtube" beats a generic browser, and chat apps beat the "focus" editors below them.
var builtinRules = []Rule{
	// entertainment: watching, listening, playing
	{"youtube", CatEntertainment}, {"netflix", CatEntertainment}, {"twitch", CatEntertainment},
	{"bilibili", CatEntertainment}, {"disney+", CatEntertainment}, {"prime video", CatEntertainment},
	{"iqiyi", CatEntertainment}, {"viu", CatEntertainment}, {"crunchyroll", CatEntertainment},
	{"spotify", CatEntertainment}, {"vlc", CatEntertainment}, {"potplayer", CatEntertainment},
	{"steam", CatEntertainment}, {"riotclient", CatEntertainment}, {"leagueclient", CatEntertainment},
	{"league of legends", CatEntertainment}, {"valorant", CatEntertainment}, {"genshin", CatEntertainment},
	// social feeds
	{"instagram", CatSocial}, {"facebook", CatSocial}, {"reddit", CatSocial}, {"tiktok", CatSocial},
	{"twitter", CatSocial}, {" / x ", CatSocial}, {"threads", CatSocial}, {"xiaohongshu", CatSocial},
	{"rednote", CatSocial}, {"linkedin", CatSocial},
	// chat
	{"whatsapp", CatChat}, {"telegram", CatChat}, {"discord", CatChat}, {"wechat", CatChat},
	{"messenger", CatChat}, {"slack", CatChat},
	// focus: building, writing, studying
	{"code.exe", CatFocus}, {"windowsterminal", CatFocus}, {"pycharm", CatFocus}, {"idea64", CatFocus},
	{"android studio", CatFocus}, {"studio64", CatFocus}, {"winword", CatFocus}, {"excel", CatFocus},
	{"powerpnt", CatFocus}, {"acrobat", CatFocus}, {".pdf", CatFocus}, {"obsidian", CatFocus},
	{"notion", CatFocus}, {"github", CatFocus}, {"gitlab", CatFocus}, {"stack overflow", CatFocus},
	{"leetcode", CatFocus}, {"overleaf", CatFocus}, {"coursera", CatFocus}, {"udemy", CatFocus},
	{"moodle", CatFocus}, {"lecture", CatFocus}, {"tutorial", CatFocus}, {"documentation", CatFocus},
	{" docs", CatFocus}, {"claude", CatFocus}, {"chatgpt", CatFocus}, {"railway", CatFocus},
}

// Classify sorts a foreground window. User rules come first so a person can say that their
// university portal is focus, or that one YouTube channel they learn from is focus.
func Classify(app, title string, user []Rule) string {
	hay := " " + strings.ToLower(app+" "+title) + " "
	for _, set := range [][]Rule{user, builtinRules} {
		for _, r := range set {
			if strings.Contains(hay, r.Needle) {
				return r.Cat
			}
		}
	}
	return CatOther
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
				out = append(out, Rule{Needle: needle, Cat: cat})
			}
		}
	}
	return out
}
