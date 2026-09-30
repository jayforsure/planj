package main

import (
	"bytes"
	"context"
	"fmt"
	"net/http"
	"strings"
)

// SendLive puts the sealed status in the relay's live slot for the phone, replacing the last.
func SendLive(ctx context.Context, client *http.Client, baseURL string, p Pairing, line []byte) error {
	blob, err := p.Seal(line)
	if err != nil {
		return err
	}
	u := strings.TrimRight(baseURL, "/") + "/v1/live/" + p.Reply
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, u, bytes.NewReader(blob))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		return fmt.Errorf("live: %s", resp.Status)
	}
	return nil
}
