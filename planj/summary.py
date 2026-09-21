import sqlite3
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


def _active_spans(conn, day_start, day_end):
    rows = conn.execute(
        "SELECT start_utc, end_utc, app FROM activity_span "
        "WHERE idle = 0 AND start_utc < ? AND end_utc > ? ORDER BY start_utc",
        (to_utc_iso(day_end), to_utc_iso(day_start)),
    ).fetchall()
    for r in rows:
        s = max(datetime.fromisoformat(r["start_utc"]), day_start)
        e = min(datetime.fromisoformat(r["end_utc"]), day_end)
        yield s, e, r["app"]


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

    per_app: dict[str, float] = defaultdict(float)
    for s, e, app in _active_spans(conn, day_start, day_end):
        secs = (e - s).total_seconds()
        out.active_s += secs
        per_app[app] += secs
        out.first_active = out.first_active or s.astimezone(tz)
        out.last_active = e.astimezone(tz)
    out.top_apps = sorted(per_app.items(), key=lambda x: -x[1])[:top_n]

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
