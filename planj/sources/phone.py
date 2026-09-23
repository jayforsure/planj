import json
import sqlite3
from pathlib import Path

from planj.db import to_utc_iso


def read_export(path: Path) -> tuple[list[tuple], list[tuple]]:
    """Splits an export into usage events and mood entries."""
    events, moods = [], []
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            r = json.loads(line)
        except json.JSONDecodeError:
            continue
        if r["event"] == "mood":
            tags = ",".join(r["tags"]) if r.get("tags") else None
            moods.append((r["day"], r["mood"], r.get("note"), to_utc_iso(r["t"]), tags))
        else:
            events.append((to_utc_iso(r["t"]), r["event"], r.get("app") or ""))
    return events, moods


def sync(conn: sqlite3.Connection, folders: list[Path]) -> tuple[int, int]:
    """Imports every phone export found. Returns (new usage events, mood entries added or updated)."""
    new_events = new_moods = 0
    for folder in folders:
        for path in sorted(folder.glob("planj-phone-*.jsonl")):
            events, moods = read_export(path)
            # Each export holds all history, so duplicates are expected and skipped.
            before = conn.total_changes
            conn.executemany("INSERT OR IGNORE INTO phone_event VALUES (?, ?, ?)", events)
            new_events += conn.total_changes - before
            before = conn.total_changes
            # Whichever entry was logged last wins, whether it came from the phone or `planj log`.
            conn.executemany(
                "INSERT INTO mood_log (day, mood, note, logged_at_utc, tags) VALUES (?, ?, ?, ?, ?) "
                "ON CONFLICT (day) DO UPDATE SET mood = excluded.mood, note = excluded.note, "
                "logged_at_utc = excluded.logged_at_utc, tags = excluded.tags "
                "WHERE excluded.logged_at_utc > mood_log.logged_at_utc",
                moods,
            )
            new_moods += conn.total_changes - before
    conn.commit()
    return new_events, new_moods
