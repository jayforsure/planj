// Command relay is a store-and-forward mailbox for end-to-end encrypted blobs.
// It never sees keys or plaintext: devices derive the mailbox ID and the encryption
// key from a pairing code only they know, and the relay just holds opaque bytes.
package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math/rand/v2"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"sync"
	"syscall"
	"time"
)

const (
	maxBlob = 4 << 20
	// The relay URL is public, so cap how much a stranger posting junk can cost us.
	// Mailbox IDs are unguessable, so real devices always land on an existing mailbox.
	maxMailboxes = 50
	maxPerBox    = 2000
	pageSize     = 20
	retention    = 30 * 24 * time.Hour
	cleanupEvery = time.Hour
)

var (
	boxRe  = regexp.MustCompile(`^[0-9a-f]{64}$`)
	itemRe = regexp.MustCompile(`^[0-9]{20}-[0-9a-f]{8}$`)
)

type server struct {
	dir  string
	mu   sync.Mutex // serialises writes so the per-mailbox cap holds
	mail mailer
}

type item struct {
	ID   string `json:"id"`
	Data string `json:"data"` // base64
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { io.WriteString(w, "ok") })
	mux.HandleFunc("POST /v1/mailbox/{box}", s.put)
	mux.HandleFunc("GET /v1/mailbox/{box}", s.list)
	mux.HandleFunc("DELETE /v1/mailbox/{box}", s.ack)
	s.accountRoutes(mux)
	return mux
}

func (s *server) boxDir(w http.ResponseWriter, r *http.Request) (string, bool) {
	box := r.PathValue("box")
	if !boxRe.MatchString(box) {
		http.Error(w, "bad mailbox id", http.StatusBadRequest)
		return "", false
	}
	return filepath.Join(s.dir, box), true
}

func items(dir string) ([]string, error) {
	entries, err := os.ReadDir(dir) // sorted by name, and names sort by arrival time
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var names []string
	for _, e := range entries {
		if itemRe.MatchString(e.Name()) {
			names = append(names, e.Name())
		}
	}
	return names, nil
}

func (s *server) put(w http.ResponseWriter, r *http.Request) {
	dir, ok := s.boxDir(w, r)
	if !ok {
		return
	}
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBlob))
	if err != nil {
		http.Error(w, "blob too large", http.StatusRequestEntityTooLarge)
		return
	}
	if len(data) == 0 {
		http.Error(w, "empty blob", http.StatusBadRequest)
		return
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	names, err := items(dir)
	if err != nil {
		serverError(w, err)
		return
	}
	if len(names) >= maxPerBox {
		http.Error(w, "mailbox full", http.StatusInsufficientStorage)
		return
	}
	if _, err := os.Stat(dir); errors.Is(err, os.ErrNotExist) {
		boxes, err := os.ReadDir(s.dir)
		if err != nil {
			serverError(w, err)
			return
		}
		if len(boxes) >= maxMailboxes {
			http.Error(w, "relay is full", http.StatusInsufficientStorage)
			return
		}
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		serverError(w, err)
		return
	}
	name := fmt.Sprintf("%020d-%08x", time.Now().UnixNano(), rand.Uint32())
	tmp := filepath.Join(dir, name+".tmp")
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		serverError(w, err)
		return
	}
	if err := os.Rename(tmp, filepath.Join(dir, name)); err != nil {
		serverError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]string{"id": name})
}

func (s *server) list(w http.ResponseWriter, r *http.Request) {
	dir, ok := s.boxDir(w, r)
	if !ok {
		return
	}
	after := r.URL.Query().Get("after")
	if after != "" && !itemRe.MatchString(after) {
		http.Error(w, "bad cursor", http.StatusBadRequest)
		return
	}
	names, err := items(dir)
	if err != nil {
		serverError(w, err)
		return
	}
	out := struct {
		Items []item `json:"items"`
		More  bool   `json:"more"`
	}{Items: []item{}}
	for _, name := range names {
		if name <= after {
			continue
		}
		if len(out.Items) == pageSize {
			out.More = true
			break
		}
		data, err := os.ReadFile(filepath.Join(dir, name))
		if errors.Is(err, os.ErrNotExist) {
			continue // acknowledged concurrently
		}
		if err != nil {
			serverError(w, err)
			return
		}
		out.Items = append(out.Items, item{ID: name, Data: base64.StdEncoding.EncodeToString(data)})
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(out)
}

// ack deletes everything up to and including the given item, once the reader has stored it.
func (s *server) ack(w http.ResponseWriter, r *http.Request) {
	dir, ok := s.boxDir(w, r)
	if !ok {
		return
	}
	upto := r.URL.Query().Get("upto")
	if !itemRe.MatchString(upto) {
		http.Error(w, "bad cursor", http.StatusBadRequest)
		return
	}
	names, err := items(dir)
	if err != nil {
		serverError(w, err)
		return
	}
	for _, name := range names {
		if name > upto {
			break
		}
		if err := os.Remove(filepath.Join(dir, name)); err != nil && !errors.Is(err, os.ErrNotExist) {
			serverError(w, err)
			return
		}
	}
	w.WriteHeader(http.StatusNoContent)
}

// cleanup removes blobs nobody collected within the retention window, and emptied mailboxes.
func (s *server) cleanup(now time.Time) {
	boxes, err := os.ReadDir(s.dir)
	if err != nil {
		return
	}
	for _, b := range boxes {
		if !b.IsDir() || !boxRe.MatchString(b.Name()) {
			continue
		}
		dir := filepath.Join(s.dir, b.Name())
		entries, _ := os.ReadDir(dir)
		left := 0
		for _, e := range entries {
			info, err := e.Info()
			if err == nil && now.Sub(info.ModTime()) > retention {
				os.Remove(filepath.Join(dir, e.Name()))
				continue
			}
			left++
		}
		if left == 0 {
			os.Remove(dir)
		}
	}
}

func serverError(w http.ResponseWriter, err error) {
	log.Printf("error: %v", err)
	http.Error(w, "internal error", http.StatusInternalServerError)
}

func main() {
	dir := os.Getenv("DATA_DIR")
	if dir == "" {
		dir = "data"
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		log.Fatal(err)
	}
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	s := &server{dir: dir, mail: mailerFromEnv()}
	go func() {
		for now := range time.Tick(cleanupEvery) {
			s.cleanup(now)
		}
	}()

	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           s.routes(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		srv.Shutdown(shutdown)
	}()
	log.Printf("relay listening on :%s, data in %s", port, dir)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
