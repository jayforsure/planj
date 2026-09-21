import json
import sqlite3
from datetime import datetime, timedelta, timezone
from urllib.parse import quote, urlencode
from urllib.request import urlopen

from planj.db import to_utc_iso

KIND_BY_TYPE = {
    "currentwindow": "window",
    "afkstatus": "afk",
    "web.tab.current": "web",
}


def _get(url: str):
    with urlopen(url, timeout=10) as resp:
        return json.load(resp)


def to_row(bucket: str, kind: str, event: dict) -> tuple:
    data = event.get("data", {})
    afk = None
    if kind == "afk":
        afk = 1 if data.get("status") == "afk" else 0
    return (
        bucket,
        event["id"],
        kind,
        to_utc_iso(event["timestamp"]),
        float(event["duration"]),
        data.get("app"),
        data.get("title"),
        data.get("url"),
        afk,
    )


def sync(conn: sqlite3.Connection, base_url: str, days: int) -> dict[str, int]:
    end = datetime.now(timezone.utc)
    start = end - timedelta(days=days)
    params = urlencode({"start": start.isoformat(), "end": end.isoformat(), "limit": -1})

    counts: dict[str, int] = {}
    for bucket_id, info in _get(f"{base_url}/api/0/buckets/").items():
        kind = KIND_BY_TYPE.get(info.get("type"))
        if kind is None:
            continue
        events = _get(f"{base_url}/api/0/buckets/{quote(bucket_id, safe='')}/events?{params}")
        rows = [to_row(bucket_id, kind, e) for e in events]
        # AW extends the latest event's duration via heartbeats, so re-synced events must overwrite.
        conn.executemany("INSERT OR REPLACE INTO aw_event VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", rows)
        counts[kind] = counts.get(kind, 0) + len(rows)
    conn.commit()
    return counts
