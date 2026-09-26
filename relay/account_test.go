package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
	"time"
)

type memMailer struct{ codes map[string]string }

var codeRe = regexp.MustCompile(`\b(\d{6})\b`)

func (m *memMailer) send(to, _ string, text string) error {
	m.codes[to] = codeRe.FindString(text)
	return nil
}

func newAccountServer(t *testing.T) (*httptest.Server, *memMailer) {
	m := &memMailer{codes: map[string]string{}}
	s := &server{dir: t.TempDir(), mail: m}
	ts := httptest.NewServer(s.routes())
	t.Cleanup(ts.Close)
	return ts, m
}

const auth1 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
const auth2 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

func call(t *testing.T, ts *httptest.Server, method, path, token string, body any) (int, map[string]any) {
	t.Helper()
	var rd *strings.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = strings.NewReader(string(b))
	} else {
		rd = strings.NewReader("")
	}
	req, _ := http.NewRequest(method, ts.URL+path, rd)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	out := map[string]any{}
	json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

func signUp(t *testing.T, ts *httptest.Server, m *memMailer, email, auth string) string {
	t.Helper()
	if st, _ := call(t, ts, "POST", "/v1/account/register", "", map[string]string{"email": email, "auth": auth}); st != 200 {
		t.Fatalf("register: %d", st)
	}
	st, out := call(t, ts, "POST", "/v1/account/verify", "", map[string]string{"email": email, "code": m.codes[email], "device": "Phone"})
	if st != 200 {
		t.Fatalf("verify: %d %v", st, out)
	}
	return out["token"].(string)
}

func TestRegisterVerifyLoginAndKeybox(t *testing.T) {
	ts, m := newAccountServer(t)
	email := "Someone@Example.com"

	// Login before verifying is refused, and the code is required.
	call(t, ts, "POST", "/v1/account/register", "", map[string]string{"email": email, "auth": auth1})
	if st, _ := call(t, ts, "POST", "/v1/account/login", "", map[string]string{"email": email, "auth": auth1}); st != 403 {
		t.Fatalf("unverified login: %d", st)
	}
	if st, _ := call(t, ts, "POST", "/v1/account/verify", "", map[string]string{"email": email, "code": "000000"}); st != 400 {
		t.Fatalf("wrong code accepted: %d", st)
	}
	st, out := call(t, ts, "POST", "/v1/account/verify", "", map[string]string{"email": email, "code": m.codes["someone@example.com"], "device": "Phone"})
	if st != 200 || out["has_keybox"] != false {
		t.Fatalf("verify: %d %v", st, out)
	}
	token := out["token"].(string)

	// Keybox round trip.
	if st, _ := call(t, ts, "GET", "/v1/account/keybox", token, nil); st != 404 {
		t.Fatalf("empty keybox: %d", st)
	}
	if st, _ := call(t, ts, "PUT", "/v1/account/keybox", token, map[string]string{"pw": "AAAA", "rc": "BBBB"}); st != 204 {
		t.Fatalf("put keybox: %d", st)
	}
	st, kb := call(t, ts, "GET", "/v1/account/keybox", token, nil)
	if st != 200 || kb["pw"] != "AAAA" || kb["rc"] != "BBBB" {
		t.Fatalf("get keybox: %d %v", st, kb)
	}

	// A second device logs in with the same auth and sees the same keybox.
	st, out = call(t, ts, "POST", "/v1/account/login", "", map[string]string{"email": "someone@example.com", "auth": auth1, "device": "PC"})
	if st != 200 || out["has_keybox"] != true {
		t.Fatalf("login: %d %v", st, out)
	}
	st, me := call(t, ts, "GET", "/v1/account/me", out["token"].(string), nil)
	if st != 200 || len(me["devices"].([]any)) != 2 {
		t.Fatalf("me: %d %v", st, me)
	}

	// Wrong password.
	if st, _ := call(t, ts, "POST", "/v1/account/login", "", map[string]string{"email": email, "auth": auth2}); st != 401 {
		t.Fatalf("wrong password: %d", st)
	}
	// Registering the same email again is refused once verified.
	if st, _ := call(t, ts, "POST", "/v1/account/register", "", map[string]string{"email": email, "auth": auth2}); st != 409 {
		t.Fatalf("duplicate register: %d", st)
	}
}

func TestForgotAndResetSignsOtherDevicesOut(t *testing.T) {
	ts, m := newAccountServer(t)
	email := "me@example.com"
	phone := signUp(t, ts, m, email, auth1)
	call(t, ts, "PUT", "/v1/account/keybox", phone, map[string]string{"pw": "AAAA", "rc": "BBBB"})

	// Unknown emails get the same answer, so the endpoint cannot be used to probe.
	if st, _ := call(t, ts, "POST", "/v1/account/forgot", "", map[string]string{"email": "nobody@example.com"}); st != 200 {
		t.Fatalf("forgot unknown: %d", st)
	}
	if st, _ := call(t, ts, "POST", "/v1/account/forgot", "", map[string]string{"email": email}); st != 200 {
		t.Fatalf("forgot: %d", st)
	}
	st, out := call(t, ts, "POST", "/v1/account/reset", "", map[string]string{"email": email, "code": m.codes[email], "auth": auth2, "device": "PC"})
	if st != 200 || out["has_keybox"] != true {
		t.Fatalf("reset: %d %v", st, out)
	}
	pc := out["token"].(string)
	if st, _ := call(t, ts, "GET", "/v1/account/me", phone, nil); st != 401 {
		t.Fatalf("old session should be gone: %d", st)
	}
	if st, _ := call(t, ts, "POST", "/v1/account/login", "", map[string]string{"email": email, "auth": auth1}); st != 401 {
		t.Fatalf("old password should fail: %d", st)
	}
	// The keybox survives for re-wrapping with the recovery code.
	if st, kb := call(t, ts, "GET", "/v1/account/keybox", pc, nil); st != 200 || kb["rc"] != "BBBB" {
		t.Fatalf("keybox after reset: %d %v", st, kb)
	}
	// A used code cannot be replayed.
	if st, _ := call(t, ts, "POST", "/v1/account/reset", "", map[string]string{"email": email, "code": m.codes[email], "auth": auth1}); st != 400 {
		t.Fatalf("replayed code: %d", st)
	}
}

func TestChangePasswordLogoutAndDelete(t *testing.T) {
	ts, m := newAccountServer(t)
	email := "me@example.com"
	phone := signUp(t, ts, m, email, auth1)
	call(t, ts, "PUT", "/v1/account/keybox", phone, map[string]string{"pw": "AAAA", "rc": "BBBB"})
	_, pcOut := call(t, ts, "POST", "/v1/account/login", "", map[string]string{"email": email, "auth": auth1, "device": "PC"})
	pc := pcOut["token"].(string)

	if st, _ := call(t, ts, "POST", "/v1/account/password", phone, map[string]string{"auth": auth2, "new_auth": auth2}); st != 401 {
		t.Fatalf("wrong current password: %d", st)
	}
	if st, _ := call(t, ts, "POST", "/v1/account/password", phone, map[string]string{"auth": auth1, "new_auth": auth2, "pw": "CCCC"}); st != 204 {
		t.Fatalf("change password: %d", st)
	}
	if st, _ := call(t, ts, "GET", "/v1/account/me", pc, nil); st != 401 {
		t.Fatalf("other device should be signed out: %d", st)
	}
	if st, kb := call(t, ts, "GET", "/v1/account/keybox", phone, nil); st != 200 || kb["pw"] != "CCCC" || kb["rc"] != "BBBB" {
		t.Fatalf("keybox after change: %d %v", st, kb)
	}
	if st, _ := call(t, ts, "POST", "/v1/account/logout", phone, nil); st != 204 {
		t.Fatalf("logout: %d", st)
	}
	if st, _ := call(t, ts, "GET", "/v1/account/me", phone, nil); st != 401 {
		t.Fatalf("after logout: %d", st)
	}
	_, again := call(t, ts, "POST", "/v1/account/login", "", map[string]string{"email": email, "auth": auth2})
	if st, _ := call(t, ts, "DELETE", "/v1/account/me", again["token"].(string), nil); st != 204 {
		t.Fatalf("delete: %d", st)
	}
	if st, _ := call(t, ts, "POST", "/v1/account/login", "", map[string]string{"email": email, "auth": auth2}); st != 401 {
		t.Fatalf("deleted account can still log in: %d", st)
	}
}

func TestCodesExpireAndLockAfterTries(t *testing.T) {
	now := time.Now()
	c := &codeChallenge{Hash: saltedHash("s", "123456"), Salt: "s", Expires: now.Add(time.Minute)}
	for i := 0; i < codeMaxTries; i++ {
		if err := checkCode(c, "000000", now); err == nil {
			t.Fatal("wrong code accepted")
		}
	}
	if err := checkCode(c, "123456", now); err == nil {
		t.Fatal("right code accepted after lockout")
	}
	fresh := &codeChallenge{Hash: saltedHash("s", "123456"), Salt: "s", Expires: now.Add(-time.Second)}
	if err := checkCode(fresh, "123456", now); err == nil {
		t.Fatal("expired code accepted")
	}
}
