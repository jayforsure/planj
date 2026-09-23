package main

// Relay sync, shared contract with the Android app (keep RelayCrypto.java identical):
//
//   code    = 20 Crockford base32 chars, shown as XXXXX-XXXXX-XXXXX-XXXXX (100 bits)
//   mailbox = hex(HKDF-SHA256(ikm=code, salt="planj-relay-v1", info="mailbox", 32))
//   key     = HKDF-SHA256(ikm=code, salt="planj-relay-v1", info="aes-256-gcm", 32)
//   blob    = 0x01 || nonce(12) || AES-256-GCM(key, nonce, gzip(jsonl), aad=mailbox)
//
// The relay only ever sees the mailbox ID and blobs; the code never leaves the two devices.

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

const (
	codeAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford: no I, L, O, U
	codeLen      = 20
	relaySalt    = "planj-relay-v1"
	blobVersion  = 1
)

type Pairing struct {
	Code    string
	Mailbox string
	key     []byte
}

func NewCode() string {
	return formatCode(randomCode())
}

func randomCode() string {
	buf := make([]byte, codeLen)
	if _, err := rand.Read(buf); err != nil {
		panic(err) // crypto/rand never fails on supported platforms
	}
	var sb strings.Builder
	for _, v := range buf {
		sb.WriteByte(codeAlphabet[v&31]) // 256 is a multiple of 32, so this is unbiased
	}
	return sb.String()
}

func formatCode(c string) string {
	return c[0:5] + "-" + c[5:10] + "-" + c[10:15] + "-" + c[15:20]
}

// NormalizeCode accepts what a person might type: any case, dashes or spaces, and
// the look-alike letters I/L (for 1) and O (for 0).
func NormalizeCode(s string) (string, error) {
	r := strings.NewReplacer("-", "", " ", "", "I", "1", "L", "1", "O", "0")
	c := r.Replace(strings.ToUpper(s))
	if len(c) != codeLen {
		return "", fmt.Errorf("pairing code must have %d characters, got %d", codeLen, len(c))
	}
	for _, ch := range c {
		if !strings.ContainsRune(codeAlphabet, ch) {
			return "", fmt.Errorf("pairing code has an invalid character %q", ch)
		}
	}
	return c, nil
}

func Derive(code string) (Pairing, error) {
	c, err := NormalizeCode(code)
	if err != nil {
		return Pairing{}, err
	}
	box, err := hkdf.Key(sha256.New, []byte(c), []byte(relaySalt), "mailbox", 32)
	if err != nil {
		return Pairing{}, err
	}
	key, err := hkdf.Key(sha256.New, []byte(c), []byte(relaySalt), "aes-256-gcm", 32)
	if err != nil {
		return Pairing{}, err
	}
	return Pairing{Code: formatCode(c), Mailbox: hex.EncodeToString(box), key: key}, nil
}

func (p Pairing) gcm() (cipher.AEAD, error) {
	block, err := aes.NewCipher(p.key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

// Seal compresses and encrypts; the Android app does the same when uploading.
func (p Pairing) Seal(jsonl []byte) ([]byte, error) {
	var z bytes.Buffer
	zw := gzip.NewWriter(&z)
	zw.Write(jsonl)
	if err := zw.Close(); err != nil {
		return nil, err
	}
	aead, err := p.gcm()
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, aead.NonceSize())
	rand.Read(nonce)
	out := append([]byte{blobVersion}, nonce...)
	return aead.Seal(out, nonce, z.Bytes(), []byte(p.Mailbox)), nil
}

func (p Pairing) Open(blob []byte) ([]byte, error) {
	aead, err := p.gcm()
	if err != nil {
		return nil, err
	}
	if len(blob) < 1+aead.NonceSize()+aead.Overhead() || blob[0] != blobVersion {
		return nil, errors.New("not a planj blob")
	}
	nonce, sealed := blob[1:1+aead.NonceSize()], blob[1+aead.NonceSize():]
	z, err := aead.Open(nil, nonce, sealed, []byte(p.Mailbox))
	if err != nil {
		return nil, err
	}
	zr, err := gzip.NewReader(bytes.NewReader(z))
	if err != nil {
		return nil, err
	}
	return io.ReadAll(io.LimitReader(zr, 64<<20))
}

type relayItem struct {
	ID   string `json:"id"`
	Data string `json:"data"`
}

// Pull downloads everything waiting in the mailbox, hands each decrypted payload to store,
// and deletes it from the relay once stored. Undecryptable items (junk someone posted to
// the mailbox) are reported via bad and dropped too, so they can't block the queue.
func Pull(ctx context.Context, client *http.Client, baseURL string, p Pairing,
	store func(id string, jsonl []byte) error, bad func(id string, err error)) (int, error) {
	boxURL := strings.TrimRight(baseURL, "/") + "/v1/mailbox/" + p.Mailbox
	stored := 0
	for {
		var page struct {
			Items []relayItem `json:"items"`
			More  bool        `json:"more"`
		}
		if err := doJSON(ctx, client, http.MethodGet, boxURL, &page); err != nil {
			return stored, err
		}
		if len(page.Items) == 0 {
			return stored, nil
		}
		for _, it := range page.Items {
			blob, err := base64.StdEncoding.DecodeString(it.Data)
			var jsonl []byte
			if err == nil {
				jsonl, err = p.Open(blob)
			}
			if err != nil {
				bad(it.ID, err)
				continue
			}
			if err := store(it.ID, jsonl); err != nil {
				return stored, err // not acknowledged, so it is retried next time
			}
			stored++
		}
		last := page.Items[len(page.Items)-1].ID
		if err := doJSON(ctx, client, http.MethodDelete, boxURL+"?upto="+url.QueryEscape(last), nil); err != nil {
			return stored, err
		}
		if !page.More {
			return stored, nil
		}
	}
}

func doJSON(ctx context.Context, client *http.Client, method, u string, out any) error {
	req, err := http.NewRequestWithContext(ctx, method, u, nil)
	if err != nil {
		return err
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return fmt.Errorf("%s %s: %s: %s", method, strings.SplitN(u, "?", 2)[0], resp.Status, strings.TrimSpace(string(msg)))
	}
	if out == nil {
		return nil
	}
	return json.NewDecoder(resp.Body).Decode(out)
}
