package main

import "strings"

// SiteFromAddress turns what a browser's address bar shows into just the site:
// "https://www.youtube.com/watch?v=abc" -> "youtube.com". The path, query and anything
// else in the address are dropped here and never kept. It returns "" for browser pages
// (edge://, about:), local files, and text that is not an address, such as a half-typed search.
func SiteFromAddress(addr string) string {
	s := strings.TrimSpace(strings.ToLower(addr))
	if s == "" || strings.ContainsAny(s, " \t") {
		return ""
	}
	if i := strings.Index(s, "://"); i >= 0 {
		if scheme := s[:i]; scheme != "http" && scheme != "https" {
			return ""
		}
		s = s[i+3:]
	} else if strings.Contains(s, ":") && !strings.Contains(strings.SplitN(s, "/", 2)[0], ".") {
		return "" // about:blank, edge:settings
	}
	if i := strings.IndexAny(s, "/?#"); i >= 0 {
		s = s[:i]
	}
	if i := strings.LastIndex(s, "@"); i >= 0 {
		s = s[i+1:] // never keep a user name
	}
	if i := strings.LastIndex(s, ":"); i >= 0 {
		s = s[:i] // port
	}
	s = strings.TrimPrefix(s, "www.")
	if !strings.Contains(s, ".") || strings.HasPrefix(s, ".") || strings.HasSuffix(s, ".") {
		return ""
	}
	for _, r := range s {
		if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '.' || r == '-') {
			return ""
		}
	}
	return s
}

// IsPrivateWindow says whether a browser window is InPrivate or incognito; those are never read.
func IsPrivateWindow(title string) bool {
	t := strings.ToLower(title)
	return strings.Contains(t, "inprivate") || strings.Contains(t, "incognito") || strings.Contains(t, "private browsing")
}
