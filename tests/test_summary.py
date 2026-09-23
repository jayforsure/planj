import json
from datetime import date
from zoneinfo import ZoneInfo

from planj.db import connect
from planj.sources import tracker
from planj.summary import summarize

KL = ZoneInfo("Asia/Kuala_Lumpur")


def _spans(conn, *spans):
    conn.executemany("INSERT INTO activity_span VALUES (?, ?, ?, ?)", spans)


def test_read_spans_skips_old_files_and_partial_lines(tmp_path):
    line = json.dumps({"start": "2026-09-21T01:00:00.000Z", "end": "2026-09-21T01:01:00.000Z", "app": "Code.exe", "idle": False})
    (tmp_path / "2026-09-21.jsonl").write_text(line + '\n{"start": "2026-09-21T01:0', encoding="utf-8")
    (tmp_path / "2026-09-01.jsonl").write_text(line + "\n", encoding="utf-8")
    (tmp_path / "notes.jsonl").write_text("ignored\n", encoding="utf-8")
    rows = tracker.read_spans(tmp_path, since=date(2026, 9, 20))
    assert rows == [("2026-09-21T01:00:00.000000+00:00", "2026-09-21T01:01:00.000000+00:00", "Code.exe", 0)]


def test_active_time_and_top_apps_exclude_idle():
    conn = connect(":memory:")
    # KL 09:00-09:30 Code, 09:30-10:00 Edge, 10:00-11:00 idle
    _spans(
        conn,
        ("2026-09-21T01:00:00.000000+00:00", "2026-09-21T01:30:00.000000+00:00", "Code.exe", 0),
        ("2026-09-21T01:30:00.000000+00:00", "2026-09-21T02:00:00.000000+00:00", "msedge.exe", 0),
        ("2026-09-21T02:00:00.000000+00:00", "2026-09-21T03:00:00.000000+00:00", "vlc.exe", 1),
    )
    s = summarize(conn, date(2026, 9, 21), KL)
    assert s.active_s == 3600
    assert dict(s.top_apps) == {"Code.exe": 1800, "msedge.exe": 1800}
    assert (s.first_active.hour, s.last_active.hour) == (9, 10)


def test_span_crossing_midnight_is_clipped_to_day():
    conn = connect(":memory:")
    # KL 23:30 on the 20th → 00:30 on the 21st
    _spans(conn, ("2026-09-20T15:30:00.000000+00:00", "2026-09-20T16:30:00.000000+00:00", "Code.exe", 0))
    assert summarize(conn, date(2026, 9, 21), KL).active_s == 1800
    assert summarize(conn, date(2026, 9, 20), KL).active_s == 1800


def test_rain_and_mood():
    conn = connect(":memory:")
    conn.executemany(
        "INSERT INTO weather_hourly VALUES (?, ?, ?, ?, ?, ?)",
        [("2026-09-21T15:00", 30, 2.0, 80, 61, "x"), ("2026-09-21T16:00", 29, 0, 20, 3, "x")],
    )
    conn.execute("INSERT INTO mood_log (day, mood, note, logged_at_utc) VALUES ('2026-09-21', 2, 'skipped prep', 'x')")
    s = summarize(conn, date(2026, 9, 21), KL)
    assert s.rainy_hours == ["15:00"]
    assert (s.mood, s.mood_note) == (2, "skipped prep")
