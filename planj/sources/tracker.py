import json
import sqlite3
from datetime import date
from pathlib import Path

from planj.db import to_utc_iso


def read_spans(folder: Path, since: date) -> list[tuple]:
    rows = []
    for path in sorted(folder.glob("*.jsonl")):
        try:
            if date.fromisoformat(path.stem) < since:
                continue
        except ValueError:
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue  # the tracker may be mid-write on the last line
            rows.append((to_utc_iso(r["start"]), to_utc_iso(r["end"]), r["app"], int(r["idle"])))
    return rows


def sync(conn: sqlite3.Connection, folder: Path, since: date) -> int:
    rows = read_spans(folder, since)
    conn.executemany("INSERT OR REPLACE INTO activity_span VALUES (?, ?, ?, ?)", rows)
    conn.commit()
    return len(rows)
