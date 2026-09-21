import sqlite3
from bisect import bisect_right
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from planj.db import to_utc_iso

RAIN_PROB_THRESHOLD = 50


@dataclass
class DaySummary:
    day: date
    active_s: float = 0.0
    first_active: datetime | None = None
    last_active: datetime | None = None
    top_apps: list[tuple[str, float]] = field(default_factory=list)
    rainy_hours: list[str] = field(default_factory=list)
    events_today: list[tuple[str, str]] = field(default_factory=list)
    events_tomorrow: list[tuple[str, str]] = field(default_factory=list)
    mood: int | None = None
    mood_note: str | None = None


def _events(conn, kind, day_start, day_end):
    # Look back a day so events that began before midnight but run into this day are included.
    rows = conn.execute(
        "SELECT start_utc, duration_s, app, afk FROM aw_event "
        "WHERE kind = ? AND start_utc >= ? AND start_utc < ?",
        (kind, to_utc_iso(day_start - timedelta(days=1)), to_utc_iso(day_end)),
    ).fetchall()
    out = []
    for r in rows:
        s = datetime.fromisoformat(r["start_utc"])
        e = s + timedelta(seconds=r["duration_s"])
        s, e = max(s, day_start), min(e, day_end)
        if e > s:
            out.append((s, e, r))
    return out


def _merge(intervals):
    merged = []
    for s, e in sorted(intervals):
        if merged and s <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], e)
        else:
            merged.append([s, e])
    return merged


def _overlap_s(merged, ends, s, e) -> float:
    total = 0.0
    for i in range(bisect_right(ends, s), len(merged)):
        ms, me = merged[i]
        if ms >= e:
            break
        total += (min(e, me) - max(s, ms)).total_seconds()
    return total


def _calendar(conn, start, end, tz) -> list[tuple[str, str]]:
    rows = conn.execute(
        "SELECT start_utc, summary, all_day FROM calendar_event "
        "WHERE start_utc < ? AND end_utc > ? ORDER BY all_day DESC, start_utc",
        (to_utc_iso(end), to_utc_iso(start)),
    ).fetchall()
    return [
        ("all day" if r["all_day"] else f"{datetime.fromisoformat(r['start_utc']).astimezone(tz):%H:%M}", r["summary"])
        for r in rows
    ]


def summarize(conn: sqlite3.Connection, day: date, tz: ZoneInfo, top_n: int = 8) -> DaySummary:
    day_start = datetime.combine(day, time.min, tz)
    day_end = day_start + timedelta(days=1)
    out = DaySummary(day=day)

    active = _merge((s, e) for s, e, r in _events(conn, "afk", day_start, day_end) if r["afk"] == 0)
    if active:
        out.active_s = sum((e - s).total_seconds() for s, e in active)
        out.first_active = active[0][0].astimezone(tz)
        out.last_active = active[-1][1].astimezone(tz)

    ends = [e for _, e in active]
    per_app: dict[str, float] = defaultdict(float)
    for s, e, r in _events(conn, "window", day_start, day_end):
        per_app[r["app"] or "(unknown)"] += _overlap_s(active, ends, s, e)
    out.top_apps = sorted(((a, t) for a, t in per_app.items() if t > 0), key=lambda x: -x[1])[:top_n]

    out.rainy_hours = [
        r["hour_local"][11:16]
        for r in conn.execute(
            "SELECT hour_local FROM weather_hourly WHERE hour_local LIKE ? AND precip_prob >= ? ORDER BY hour_local",
            (f"{day.isoformat()}T%", RAIN_PROB_THRESHOLD),
        )
    ]

    out.events_today = _calendar(conn, day_start, day_end, tz)
    out.events_tomorrow = _calendar(conn, day_end, day_end + timedelta(days=1), tz)

    mood = conn.execute("SELECT mood, note FROM mood_log WHERE day = ?", (day.isoformat(),)).fetchone()
    if mood:
        out.mood, out.mood_note = mood["mood"], mood["note"]
    return out
