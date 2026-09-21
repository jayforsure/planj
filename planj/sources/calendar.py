import sqlite3
from datetime import date, datetime, time, timedelta
from urllib.request import urlopen
from zoneinfo import ZoneInfo

import icalendar
import recurring_ical_events

from planj.db import to_utc_iso


def _as_dt(value: date | datetime, tz: ZoneInfo) -> datetime:
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=tz)
    return datetime.combine(value, time.min, tz)


def parse(ics: bytes, start: datetime, end: datetime, tz: ZoneInfo) -> list[tuple]:
    cal = icalendar.Calendar.from_ical(ics)
    rows = []
    for ev in recurring_ical_events.of(cal).between(start, end):
        if str(ev.get("STATUS", "")).upper() == "CANCELLED":
            continue
        dtstart = ev["DTSTART"].dt
        if "DTEND" in ev:
            dtend = ev["DTEND"].dt
        elif "DURATION" in ev:
            dtend = dtstart + ev["DURATION"].dt
        else:
            dtend = dtstart
        rows.append((
            str(ev.get("UID", "")),
            to_utc_iso(_as_dt(dtstart, tz)),
            to_utc_iso(_as_dt(dtend, tz)),
            str(ev.get("SUMMARY", "")),
            0 if isinstance(dtstart, datetime) else 1,
        ))
    return rows


def sync(conn: sqlite3.Connection, url: str, tz: ZoneInfo, past_days: int = 7, future_days: int = 14) -> int:
    now = datetime.now(tz)
    start, end = now - timedelta(days=past_days), now + timedelta(days=future_days)
    with urlopen(url, timeout=15) as resp:
        rows = parse(resp.read(), start, end, tz)
    # Replace the whole window so events deleted or moved in the calendar disappear here too.
    conn.execute(
        "DELETE FROM calendar_event WHERE start_utc >= ? AND start_utc < ?",
        (to_utc_iso(start), to_utc_iso(end)),
    )
    conn.executemany("INSERT OR REPLACE INTO calendar_event VALUES (?, ?, ?, ?, ?)", rows)
    conn.commit()
    return len(rows)
