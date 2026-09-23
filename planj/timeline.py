"""Rebuilds what happened on each device, from stored spans and phone events."""

import sqlite3
from datetime import datetime, timedelta

from planj.db import to_utc_iso

Interval = tuple[datetime, datetime]


def _clip(s: datetime, e: datetime, start: datetime, end: datetime) -> Interval | None:
    s, e = max(s, start), min(e, end)
    return (s, e) if e > s else None


def pc_active(conn: sqlite3.Connection, start: datetime, end: datetime) -> list[tuple[datetime, datetime, str]]:
    """Spans where the PC was in use, i.e. not idle, clipped to the window."""
    rows = conn.execute(
        "SELECT start_utc, end_utc, app FROM activity_span "
        "WHERE idle = 0 AND start_utc < ? AND end_utc > ? ORDER BY start_utc",
        (to_utc_iso(end), to_utc_iso(start)),
    ).fetchall()
    out = []
    for r in rows:
        span = _clip(datetime.fromisoformat(r["start_utc"]), datetime.fromisoformat(r["end_utc"]), start, end)
        if span:
            out.append((*span, r["app"]))
    return out


def _phone_events(conn: sqlite3.Connection, start: datetime, end: datetime):
    # A day either side, so sessions that cross midnight have both ends.
    rows = conn.execute(
        "SELECT t_utc, event, app FROM phone_event WHERE t_utc >= ? AND t_utc < ? ORDER BY t_utc",
        (to_utc_iso(start - timedelta(days=1)), to_utc_iso(end + timedelta(days=1))),
    ).fetchall()
    for r in rows:
        yield datetime.fromisoformat(r["t_utc"]), r["event"], r["app"]


def phone_screen(conn: sqlite3.Connection, start: datetime, end: datetime) -> list[Interval]:
    """Periods with the screen on, clipped to the window."""
    out, on_since = [], None
    for t, event, _ in _phone_events(conn, start, end):
        if event == "screen_on":
            on_since = on_since or t
        elif event in ("screen_off", "shutdown") and on_since is not None:
            if span := _clip(on_since, t, start, end):
                out.append(span)
            on_since = None
    return out


def phone_apps(conn: sqlite3.Connection, start: datetime, end: datetime) -> list[tuple[datetime, datetime, str]]:
    """Foreground app spans on the phone, clipped to the window."""
    out, app, since = [], None, None

    def close(t):
        nonlocal app, since
        if app is not None and (span := _clip(since, t, start, end)):
            out.append((*span, app))
        app = since = None

    for t, event, pkg in _phone_events(conn, start, end):
        if event in ("screen_off", "shutdown"):
            close(t)
        elif event == "app_fg" and pkg != app:
            close(t)
            app, since = pkg, t
        elif event == "app_bg" and pkg == app:
            close(t)
    return out


def phone_unlocks(conn: sqlite3.Connection, start: datetime, end: datetime) -> int:
    return conn.execute(
        "SELECT COUNT(*) FROM phone_event WHERE event = 'unlock' AND t_utc >= ? AND t_utc < ?",
        (to_utc_iso(start), to_utc_iso(end)),
    ).fetchone()[0]


def merge(intervals: list[Interval]) -> list[Interval]:
    merged: list[list[datetime]] = []
    for s, e in sorted(intervals):
        if merged and s <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], e)
        else:
            merged.append([s, e])
    return [(s, e) for s, e in merged]


def total_seconds(intervals: list[Interval]) -> float:
    return sum((e - s).total_seconds() for s, e in intervals)
