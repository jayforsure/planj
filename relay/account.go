// Accounts: email + password sign-in with verification and reset codes by email.
//
// The relay never learns the password or the data key. Devices send an "auth" value
// derived from the password (PBKDF2 then HKDF); the relay stores a salted hash of it.
// The data key travels only inside the "keybox": ciphertext the device wrapped with
// another derivation of the password, and again with the recovery code. Whoever can
// log in can fetch the keybox, but only the password or the recovery code opens it.
package main

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/mail"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	codeTTL        = 15 * time.Minute
	codeCooldown   = 60 * time.Second
	codeMaxTries   = 5
	tokenTTL       = 180 * 24 * time.Hour
	maxAccounts    = 500
	maxDevices     = 10
	maxKeyboxBytes = 4096
	maxBody        = 16 << 10
)

type codeChallenge struct {
	Hash    string    `json:"hash"` // sha256(salt||code)
	Salt    string    `json:"salt"`
	Expires time.Time `json:"expires"`
	SentAt  time.Time `json:"sent_at"`
	Tries   int       `json:"tries"`
}

type device struct {
	TokenHash string    `json:"token_hash"`
	Name      string    `json:"name"`
	Created   time.Time `json:"created"`
	LastSeen  time.Time `json:"last_seen"`
}

type account struct {
	Email    string         `json:"email"`
	AuthSalt string         `json:"auth_salt"`
	AuthHash string         `json:"auth_hash"`
	Verified bool           `json:"verified"`
	Created  time.Time      `json:"created"`
	Verify   *codeChallenge `json:"verify,omitempty"`
	Reset    *codeChallenge `json:"reset,omitempty"`
	Keybox   *keybox        `json:"keybox,omitempty"`
	Devices  []device       `json:"devices"`
}

type keybox struct {
	PW      string    `json:"pw"`           // account key wrapped with the password-derived key
	RC      string    `json:"rc,omitempty"` // account key wrapped with the recovery-code-derived key
	Updated time.Time `json:"updated"`
}

// mailer delivers a one-time code. The default logs it, which is what local runs want.
type mailer interface {
	send(to, subject, text string) error
}

type logMailer struct{}

func (logMailer) send(to, subject, text string) error {
	log.Printf("mail to %s: %s\n%s", to, subject, text)
	return nil
}

// resendMailer posts through resend.com; RESEND_API_KEY and MAIL_FROM select it.
type resendMailer struct {
	key, from string
	client    *http.Client
}

func (m resendMailer) send(to, subject, text string) error {
	body, _ := json.Marshal(map[string]any{"from": m.from, "to": []string{to}, "subject": subject, "text": text})
	req, err := http.NewRequest("POST", "https://api.resend.com/emails", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+m.key)
	req.Header.Set("Content-Type", "application/json")
	resp, err := m.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("mail provider answered %d: %s", resp.StatusCode, strings.TrimSpace(string(msg)))
	}
	return nil
}

func mailerFromEnv() mailer {
	key, from := os.Getenv("RESEND_API_KEY"), os.Getenv("MAIL_FROM")
	if key != "" && from != "" {
		return resendMailer{key: key, from: from, client: &http.Client{Timeout: 20 * time.Second}}
	}
	log.Printf("no mail provider configured (RESEND_API_KEY, MAIL_FROM): codes are logged instead")
	return logMailer{}
}

// ---- storage -----------------------------------------------------------

func (s *server) accountsDir() string { return filepath.Join(s.dir, "accounts") }

func accountPath(dir, email string) string {
	sum := sha256.Sum256([]byte(email))
	return filepath.Join(dir, hex.EncodeToString(sum[:])+".json")
}

func normaliseEmail(e string) (string, bool) {
	e = strings.ToLower(strings.TrimSpace(e))
	if e == "" || len(e) > 254 {
		return "", false
	}
	addr, err := mail.ParseAddress(e)
	if err != nil || addr.Address != e {
		return "", false
	}
	return e, true
}

func (s *server) loadAccount(email string) (*account, error) {
	b, err := os.ReadFile(accountPath(s.accountsDir(), email))
	if err != nil {
		return nil, err
	}
	var a account
	if err := json.Unmarshal(b, &a); err != nil {
		return nil, err
	}
	return &a, nil
}

func (s *server) saveAccount(a *account) error {
	dir := s.accountsDir()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	b, err := json.Marshal(a)
	if err != nil {
		return err
	}
	p := accountPath(dir, a.Email)
	if err := os.WriteFile(p+".tmp", b, 0o600); err != nil {
		return err
	}
	return os.Rename(p+".tmp", p)
}

func (s *server) countAccounts() int {
	entries, _ := os.ReadDir(s.accountsDir())
	n := 0
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".json") {
			n++
		}
	}
	return n
}

// ---- hashing helpers ---------------------------------------------------

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

func saltedHash(salt, value string) string {
	sum := sha256.Sum256([]byte(salt + "|" + value))
	return hex.EncodeToString(sum[:])
}

func hashEqual(a, b string) bool {
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

func newCode() string {
	b := make([]byte, 4)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	n := uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])
	return fmt.Sprintf("%06d", n%1_000_000)
}

func (s *server) issueCode(a *account, slot **codeChallenge, subject, text string, now time.Time) error {
	if *slot != nil && now.Sub((*slot).SentAt) < codeCooldown {
		return errCooldown
	}
	code := newCode()
	salt := randomHex(8)
	*slot = &codeChallenge{Hash: saltedHash(salt, code), Salt: salt, Expires: now.Add(codeTTL), SentAt: now}
	if err := s.saveAccount(a); err != nil {
		return err
	}
	return s.mail.send(a.Email, subject, strings.ReplaceAll(text, "{code}", code))
}

var (
	errCooldown = errors.New("a code was sent less than a minute ago")
	errBadCode  = errors.New("wrong or expired code")
)

func checkCode(c *codeChallenge, typed string, now time.Time) error {
	if c == nil || now.After(c.Expires) || c.Tries >= codeMaxTries {
		return errBadCode
	}
	c.Tries++
	if !hashEqual(c.Hash, saltedHash(c.Salt, strings.TrimSpace(typed))) {
		return errBadCode
	}
	return nil
}

// authValue must look like the client's HKDF output: 64 hex characters.
func validAuth(v string) bool {
	if len(v) != 64 {
		return false
	}
	_, err := hex.DecodeString(v)
	return err == nil
}

// ---- tokens ------------------------------------------------------------

func (s *server) newToken(a *account, name string, now time.Time) string {
	token := randomHex(32)
	name = strings.TrimSpace(name)
	if name == "" {
		name = "Device"
	}
	if len(name) > 40 {
		name = name[:40]
	}
	a.Devices = append(a.Devices, device{TokenHash: saltedHash("token", token), Name: name, Created: now, LastSeen: now})
	if len(a.Devices) > maxDevices {
		a.Devices = a.Devices[len(a.Devices)-maxDevices:]
	}
	return token
}

// authed resolves the bearer token to an account and the device index, touching last_seen.
func (s *server) authed(w http.ResponseWriter, r *http.Request) (*account, int, bool) {
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, "Bearer ") {
		http.Error(w, "sign in first", http.StatusUnauthorized)
		return nil, -1, false
	}
	raw := strings.TrimPrefix(h, "Bearer ")
	dot := strings.LastIndex(raw, ".") // the email part has dots of its own
	if dot < 0 {
		http.Error(w, "sign in first", http.StatusUnauthorized)
		return nil, -1, false
	}
	parts := []string{raw[:dot], raw[dot+1:]}
	email, ok := normaliseEmail(parts[0])
	if !ok {
		http.Error(w, "sign in first", http.StatusUnauthorized)
		return nil, -1, false
	}
	a, err := s.loadAccount(email)
	if err != nil {
		http.Error(w, "sign in first", http.StatusUnauthorized)
		return nil, -1, false
	}
	want := saltedHash("token", parts[1])
	now := time.Now()
	for i, d := range a.Devices {
		if hashEqual(d.TokenHash, want) {
			if now.Sub(d.Created) > tokenTTL {
				break
			}
			a.Devices[i].LastSeen = now
			return a, i, true
		}
	}
	http.Error(w, "session expired, sign in again", http.StatusUnauthorized)
	return nil, -1, false
}

// ---- handlers ----------------------------------------------------------

func (s *server) accountRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/account/register", s.register)
	mux.HandleFunc("POST /v1/account/verify", s.verify)
	mux.HandleFunc("POST /v1/account/resend", s.resend)
	mux.HandleFunc("POST /v1/account/login", s.login)
	mux.HandleFunc("POST /v1/account/forgot", s.forgot)
	mux.HandleFunc("POST /v1/account/reset", s.reset)
	mux.HandleFunc("GET /v1/account/me", s.me)
	mux.HandleFunc("DELETE /v1/account/me", s.deleteMe)
	mux.HandleFunc("POST /v1/account/logout", s.logout)
	mux.HandleFunc("POST /v1/account/password", s.changePassword)
	mux.HandleFunc("GET /v1/account/keybox", s.getKeybox)
	mux.HandleFunc("PUT /v1/account/keybox", s.putKeybox)
	mux.HandleFunc("DELETE /v1/account/device/{index}", s.revokeDevice)
}

type accountRequest struct {
	Email   string `json:"email"`
	Auth    string `json:"auth"`
	NewAuth string `json:"new_auth"`
	Code    string `json:"code"`
	Device  string `json:"device"`
	PW      string `json:"pw"`
	RC      string `json:"rc"`
}

func readJSON(w http.ResponseWriter, r *http.Request, into any) bool {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBody))
	if err != nil {
		http.Error(w, "request too large", http.StatusRequestEntityTooLarge)
		return false
	}
	if err := json.Unmarshal(body, into); err != nil {
		http.Error(w, "bad json", http.StatusBadRequest)
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func (s *server) register(w http.ResponseWriter, r *http.Request) {
	var req accountRequest
	if !readJSON(w, r, &req) {
		return
	}
	email, ok := normaliseEmail(req.Email)
	if !ok {
		http.Error(w, "that does not look like an email address", http.StatusBadRequest)
		return
	}
	if !validAuth(req.Auth) {
		http.Error(w, "bad auth value", http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	a, err := s.loadAccount(email)
	switch {
	case err == nil && a.Verified:
		http.Error(w, "an account with this email already exists — sign in, or use “forgot password”", http.StatusConflict)
		return
	case err == nil:
		// Unverified: let them start over with a fresh password, keeping the cooldown.
		a.AuthSalt = randomHex(16)
		a.AuthHash = saltedHash(a.AuthSalt, req.Auth)
	case errors.Is(err, os.ErrNotExist):
		if s.countAccounts() >= maxAccounts {
			http.Error(w, "no room for new accounts right now", http.StatusInsufficientStorage)
			return
		}
		salt := randomHex(16)
		a = &account{Email: email, AuthSalt: salt, AuthHash: saltedHash(salt, req.Auth), Created: now, Devices: []device{}}
	default:
		serverError(w, err)
		return
	}
	err = s.issueCode(a, &a.Verify, "Your planj verification code",
		"Your code is {code}\n\nIt works for 15 minutes. If you did not create a planj account, ignore this email.", now)
	if errors.Is(err, errCooldown) {
		http.Error(w, err.Error(), http.StatusTooManyRequests)
		return
	}
	if err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *server) resend(w http.ResponseWriter, r *http.Request) {
	var req accountRequest
	if !readJSON(w, r, &req) {
		return
	}
	email, ok := normaliseEmail(req.Email)
	if !ok {
		http.Error(w, "that does not look like an email address", http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	a, err := s.loadAccount(email)
	if err != nil || a.Verified {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true}) // never reveal which emails exist
		return
	}
	err = s.issueCode(a, &a.Verify, "Your planj verification code",
		"Your code is {code}\n\nIt works for 15 minutes.", time.Now())
	if errors.Is(err, errCooldown) {
		http.Error(w, err.Error(), http.StatusTooManyRequests)
		return
	}
	if err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *server) verify(w http.ResponseWriter, r *http.Request) {
	var req accountRequest
	if !readJSON(w, r, &req) {
		return
	}
	email, ok := normaliseEmail(req.Email)
	if !ok {
		http.Error(w, "that does not look like an email address", http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	a, err := s.loadAccount(email)
	if err != nil {
		http.Error(w, "wrong or expired code", http.StatusBadRequest)
		return
	}
	now := time.Now()
	if err := checkCode(a.Verify, req.Code, now); err != nil {
		s.saveAccount(a) // records the try
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	a.Verified = true
	a.Verify = nil
	token := s.newToken(a, req.Device, now)
	if err := s.saveAccount(a); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"token": email + "." + token, "has_keybox": a.Keybox != nil})
}

func (s *server) login(w http.ResponseWriter, r *http.Request) {
	var req accountRequest
	if !readJSON(w, r, &req) {
		return
	}
	email, ok := normaliseEmail(req.Email)
	if !ok || !validAuth(req.Auth) {
		http.Error(w, "wrong email or password", http.StatusUnauthorized)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	a, err := s.loadAccount(email)
	if err != nil || !hashEqual(a.AuthHash, saltedHash(a.AuthSalt, req.Auth)) {
		http.Error(w, "wrong email or password", http.StatusUnauthorized)
		return
	}
	if !a.Verified {
		http.Error(w, "email not verified yet", http.StatusForbidden)
		return
	}
	token := s.newToken(a, req.Device, time.Now())
	if err := s.saveAccount(a); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"token": email + "." + token, "has_keybox": a.Keybox != nil})
}

func (s *server) forgot(w http.ResponseWriter, r *http.Request) {
	var req accountRequest
	if !readJSON(w, r, &req) {
		return
	}
	email, ok := normaliseEmail(req.Email)
	if !ok {
		http.Error(w, "that does not look like an email address", http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	a, err := s.loadAccount(email)
	if err != nil || !a.Verified {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true}) // same answer whether or not the account exists
		return
	}
	err = s.issueCode(a, &a.Reset, "Reset your planj password",
		"Your reset code is {code}\n\nIt works for 15 minutes. If you did not ask for this, you can ignore it — your password stays the same.", time.Now())
	if errors.Is(err, errCooldown) {
		http.Error(w, err.Error(), http.StatusTooManyRequests)
		return
	}
	if err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// reset sets a new password. Every other device is signed out; the keybox is kept so the
// device can re-wrap the data key with the recovery code, or replace it.
func (s *server) reset(w http.ResponseWriter, r *http.Request) {
	var req accountRequest
	if !readJSON(w, r, &req) {
		return
	}
	email, ok := normaliseEmail(req.Email)
	if !ok || !validAuth(req.Auth) {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	a, err := s.loadAccount(email)
	if err != nil {
		http.Error(w, "wrong or expired code", http.StatusBadRequest)
		return
	}
	now := time.Now()
	if err := checkCode(a.Reset, req.Code, now); err != nil {
		s.saveAccount(a)
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	a.Reset = nil
	a.Verified = true
	a.AuthSalt = randomHex(16)
	a.AuthHash = saltedHash(a.AuthSalt, req.Auth)
	a.Devices = []device{}
	token := s.newToken(a, req.Device, now)
	if err := s.saveAccount(a); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"token": email + "." + token, "has_keybox": a.Keybox != nil})
}

func (s *server) me(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	a, idx, ok := s.authed(w, r)
	if !ok {
		return
	}
	s.saveAccount(a)
	type dev struct {
		Index    int       `json:"index"`
		Name     string    `json:"name"`
		Created  time.Time `json:"created"`
		LastSeen time.Time `json:"last_seen"`
		This     bool      `json:"this"`
	}
	devs := []dev{}
	for i, d := range a.Devices {
		devs = append(devs, dev{Index: i, Name: d.Name, Created: d.Created, LastSeen: d.LastSeen, This: i == idx})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"email": a.Email, "created": a.Created, "has_keybox": a.Keybox != nil,
		"has_recovery": a.Keybox != nil && a.Keybox.RC != "", "devices": devs,
	})
}

func (s *server) logout(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	a, idx, ok := s.authed(w, r)
	if !ok {
		return
	}
	a.Devices = append(a.Devices[:idx], a.Devices[idx+1:]...)
	if err := s.saveAccount(a); err != nil {
		serverError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) revokeDevice(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	a, _, ok := s.authed(w, r)
	if !ok {
		return
	}
	var i int
	if _, err := fmt.Sscanf(r.PathValue("index"), "%d", &i); err != nil || i < 0 || i >= len(a.Devices) {
		http.Error(w, "no such device", http.StatusNotFound)
		return
	}
	a.Devices = append(a.Devices[:i], a.Devices[i+1:]...)
	if err := s.saveAccount(a); err != nil {
		serverError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) deleteMe(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	a, _, ok := s.authed(w, r)
	if !ok {
		return
	}
	if err := os.Remove(accountPath(s.accountsDir(), a.Email)); err != nil && !errors.Is(err, os.ErrNotExist) {
		serverError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) changePassword(w http.ResponseWriter, r *http.Request) {
	var req accountRequest
	if !readJSON(w, r, &req) {
		return
	}
	if !validAuth(req.Auth) || !validAuth(req.NewAuth) {
		http.Error(w, "bad auth value", http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	a, idx, ok := s.authed(w, r)
	if !ok {
		return
	}
	if !hashEqual(a.AuthHash, saltedHash(a.AuthSalt, req.Auth)) {
		http.Error(w, "current password is wrong", http.StatusUnauthorized)
		return
	}
	a.AuthSalt = randomHex(16)
	a.AuthHash = saltedHash(a.AuthSalt, req.NewAuth)
	if req.PW != "" { // the client re-wrapped the data key with the new password in the same step
		if a.Keybox == nil {
			a.Keybox = &keybox{}
		}
		a.Keybox.PW = req.PW
		a.Keybox.Updated = time.Now()
	}
	a.Devices = []device{a.Devices[idx]} // everyone else must sign in again
	if err := s.saveAccount(a); err != nil {
		serverError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) getKeybox(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	a, _, ok := s.authed(w, r)
	if !ok {
		return
	}
	s.saveAccount(a)
	if a.Keybox == nil {
		http.Error(w, "no keybox yet", http.StatusNotFound)
		return
	}
	writeJSON(w, http.StatusOK, a.Keybox)
}

func (s *server) putKeybox(w http.ResponseWriter, r *http.Request) {
	var req accountRequest
	if !readJSON(w, r, &req) {
		return
	}
	if req.PW == "" || len(req.PW) > maxKeyboxBytes || len(req.RC) > maxKeyboxBytes {
		http.Error(w, "bad keybox", http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	a, _, ok := s.authed(w, r)
	if !ok {
		return
	}
	a.Keybox = &keybox{PW: req.PW, RC: req.RC, Updated: time.Now()}
	if err := s.saveAccount(a); err != nil {
		serverError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
