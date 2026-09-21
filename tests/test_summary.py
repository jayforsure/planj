from datetime import date
from zoneinfo import ZoneInfo

from planj.db import connect
from planj.sources.activitywatch import to_row
from planj.summary import summarize

KL = ZoneInfo("Asia/Kuala_Lumpur")


def _insert(conn, bucket, kind, events):
    conn.executemany(
        "INSERT OR REPLACE INTO aw_event VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [to_row(bucket, kind, e) for e in events],
    )


def test_to_row_parses_afk_and_window():
    afk = to_row("aw-watcher-afk_pc", "afk", {"id": 1, "timestamp": "2026-09-21T01:00:00+00:00", "duration": 60, "data": {"status": "not-afk"}})
    assert afk[3] == "2026-09-21T01:00:00.000000+00:00"
    assert afk[8] == 0
    win = to_row("aw-watcher-window_pc", "window", {"id": 2, "timestamp": "2026-09-21T01:00:00.5+00:00", "duration": 1.5, "data": {"app": "Code.exe", "title": "x"}})
    assert win[5] == "Code.exe" and win[8] is None


def test_app_time_only_counts_non_afk_overlap():
    conn = connect(":memory:")
    # KL 09:00-10:00 active, 10:00-11:00 away
    _insert(conn, "afk", "afk", [
        {"id": 1, "timestamp": "2026-09-21T01:00:00+00:00", "duration": 3600, "data": {"status": "not-afk"}},
        {"id": 2, "timestamp": "2026-09-21T02:00:00+00:00", "duration": 3600, "data": {"status": "afk"}},
    ])
    # Code open 09:30-10:30 → only 30 min is while active
    _insert(conn, "win", "window", [
        {"id": 1, "timestamp": "2026-09-21T01:00:00+00:00", "duration": 1800, "data": {"app": "chrome.exe"}},
        {"id": 2, "timestamp": "2026-09-21T01:30:00+00:00", "duration": 3600, "data": {"app": "Code.exe"}},
    ])
    s = summarize(conn, date(2026, 9, 21), KL)
    assert s.active_s == 3600
    assert dict(s.top_apps) == {"chrome.exe": 1800, "Code.exe": 1800}
    assert s.first_active.hour == 9 and s.last_active.hour == 10


def test_event_crossing_midnight_is_clipped_to_day():
    conn = connect(":memory:")
    # KL 23:30 on the 20th → 00:30 on the 21st
    _insert(conn, "afk", "afk", [
        {"id": 1, "timestamp": "2026-09-20T15:30:00+00:00", "duration": 3600, "data": {"status": "not-afk"}},
    ])
    assert summarize(conn, date(2026, 9, 21), KL).active_s == 1800
    assert summarize(conn, date(2026, 9, 20), KL).active_s == 1800


def test_rain_and_mood():
    conn = connect(":memory:")
    conn.executemany(
        "INSERT INTO weather_hourly VALUES (?, ?, ?, ?, ?, ?)",
        [("2026-09-21T15:00", 30, 2.0, 80, 61, "x"), ("2026-09-21T16:00", 29, 0, 20, 3, "x")],
    )
    conn.execute("INSERT INTO mood_log VALUES ('2026-09-21', 2, 'skipped prep', 'x')")
    s = summarize(conn, date(2026, 9, 21), KL)
    assert s.rainy_hours == ["15:00"]
    assert (s.mood, s.mood_note) == (2, "skipped prep")
