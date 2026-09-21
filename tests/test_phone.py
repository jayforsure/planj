import json
from datetime import date
from zoneinfo import ZoneInfo

from planj.db import connect
from planj.sources import phone
from planj.summary import summarize

KL = ZoneInfo("Asia/Kuala_Lumpur")

# KL times: screen on 09:00, YouTube 09:00-09:40, WhatsApp 09:40-09:50, screen off 10:00
EVENTS = [
    {"t": "2026-09-21T01:00:00Z", "event": "screen_on"},
    {"t": "2026-09-21T01:00:05Z", "event": "unlock"},
    {"t": "2026-09-21T01:00:10Z", "event": "app_fg", "app": "com.google.android.youtube"},
    {"t": "2026-09-21T01:40:10Z", "event": "app_fg", "app": "com.whatsapp"},
    {"t": "2026-09-21T01:40:11Z", "event": "app_bg", "app": "com.google.android.youtube"},
    {"t": "2026-09-21T01:50:10Z", "event": "app_bg", "app": "com.whatsapp"},
    {"t": "2026-09-21T02:00:00Z", "event": "screen_off"},
]


def _write_export(folder, name, events):
    (folder / name).write_text("".join(json.dumps(e) + "\n" for e in events), encoding="utf-8")


def test_sync_imports_exports_and_skips_duplicates(tmp_path):
    conn = connect(":memory:")
    _write_export(tmp_path, "planj-phone-2026-09-21.jsonl", EVENTS)
    _write_export(tmp_path, "planj-phone-2026-09-22.jsonl", EVENTS + [{"t": "2026-09-21T03:00:00Z", "event": "unlock"}])
    _write_export(tmp_path, "unrelated.jsonl", EVENTS)
    assert phone.sync(conn, [tmp_path]) == (len(EVENTS) + 1, 0)
    assert phone.sync(conn, [tmp_path]) == (0, 0)


def _mood(day, mood, t, note=None):
    m = {"t": t, "event": "mood", "day": day, "mood": mood}
    if note:
        m["note"] = note
    return m


def test_latest_mood_wins_across_phone_and_pc(tmp_path):
    conn = connect(":memory:")
    # Changed their mind on the phone: 2 then 4. The export lists both.
    _write_export(tmp_path, "planj-phone-a.jsonl", [
        _mood("2026-09-21", 2, "2026-09-21T13:00:00Z", "lost on a trade"),
        _mood("2026-09-21", 4, "2026-09-21T14:00:00Z"),
    ])
    assert phone.sync(conn, [tmp_path]) == (0, 2)
    assert tuple(conn.execute("SELECT mood, note FROM mood_log").fetchone()) == (4, None)

    # A later `planj log` on the PC beats the older phone entries on re-import.
    conn.execute("UPDATE mood_log SET mood = 3, logged_at_utc = '2026-09-21T15:00:00.000000+00:00'")
    assert phone.sync(conn, [tmp_path]) == (0, 0)
    assert conn.execute("SELECT mood FROM mood_log").fetchone()[0] == 3


def test_summary_rebuilds_screen_and_app_time(tmp_path):
    conn = connect(":memory:")
    _write_export(tmp_path, "planj-phone-x.jsonl", EVENTS)
    phone.sync(conn, [tmp_path])
    s = summarize(conn, date(2026, 9, 21), KL)
    assert s.phone_screen_s == 3600
    assert s.phone_unlocks == 1
    assert dict(s.phone_top_apps) == {"com.google.android.youtube": 2400, "com.whatsapp": 600}


def test_screen_session_crossing_midnight_is_clipped(tmp_path):
    conn = connect(":memory:")
    # KL 23:30 → 00:30
    _write_export(tmp_path, "planj-phone-x.jsonl", [
        {"t": "2026-09-20T15:30:00Z", "event": "screen_on"},
        {"t": "2026-09-20T16:30:00Z", "event": "screen_off"},
    ])
    phone.sync(conn, [tmp_path])
    assert summarize(conn, date(2026, 9, 21), KL).phone_screen_s == 1800
    assert summarize(conn, date(2026, 9, 20), KL).phone_screen_s == 1800
