import json
import sqlite3
from pathlib import Path

from planj.db import to_utc_iso


def read_events(path: Path) -> list[tuple]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            r = json.loads(line)
        except json.JSONDecodeError:
            continue
        rows.append((to_utc_iso(r["t"]), r["event"], r.get("app") or ""))
    return rows


def sync(conn: sqlite3.Connection, folders: list[Path]) -> int:
    """Imports every phone export found; each export holds all history, so duplicates are skipped."""
    before = conn.total_changes
    for folder in folders:
        for path in sorted(folder.glob("planj-phone-*.jsonl")):
            conn.executemany("INSERT OR IGNORE INTO phone_event VALUES (?, ?, ?)", read_events(path))
    conn.commit()
    return conn.total_changes - before
