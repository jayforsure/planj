package main

import (
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestLiveSlotKeepsOnlyTheLatest(t *testing.T) {
	_, ts := newTestServer(t)
	get := func() (int, string) {
		resp, err := http.Get(ts.URL + "/v1/live/" + box)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(b)
	}
	putLive := func(b, body string) int {
		req, _ := http.NewRequest(http.MethodPut, ts.URL+"/v1/live/"+b, strings.NewReader(body))
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	if code, _ := get(); code != http.StatusNoContent {
		t.Fatalf("empty slot: %d", code)
	}
	if putLive(box, "first") != http.StatusNoContent || putLive(box, "second") != http.StatusNoContent {
		t.Fatal("put failed")
	}
	if code, body := get(); code != http.StatusOK || body != "second" {
		t.Fatalf("want the latest only, got %d %q", code, body)
	}
	if putLive(box, "") != http.StatusBadRequest {
		t.Fatal("empty blob accepted")
	}
	if putLive("not-a-box", "x") != http.StatusBadRequest {
		t.Fatal("bad box accepted")
	}
}

func TestLiveSlotExpiresAndMakesRoom(t *testing.T) {
	var l liveStore
	t0 := time.Now()
	l.put(box, []byte("x"), t0)
	if _, ok := l.get(box, t0.Add(liveTTL+time.Second)); ok {
		t.Fatal("a slot nobody wrote for longer than the TTL is not live")
	}
	for i := 0; i < maxLiveSlots; i++ {
		l.put(strings.Repeat("0", 60)+string(rune('a'+i%26))+string(rune('a'+i/26%26))+"00", []byte("x"), t0)
	}
	if l.put(strings.Repeat("f", 64), []byte("x"), t0) {
		t.Fatal("full store should refuse a new slot while all are fresh")
	}
	if !l.put(strings.Repeat("f", 64), []byte("x"), t0.Add(liveTTL+time.Minute)) {
		t.Fatal("stale slots should make room")
	}
}
