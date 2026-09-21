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
    phone_screen_s: float = 0.0
    phone_unlocks: int = 0
    phone_top_apps: list[tuple[str, float]] = field(default_factory=list)
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


def _phone_usage(conn, day_start, day_end) -> tuple[float, int, dict[str, float]]:
    """Rebuilds screen-on and per-app time from raw phone events, clipped to the day."""
    rows = conn.execute(
        "SELECT t_utc, event, app FROM phone_event WHERE t_utc >= ? AND t_utc < ? ORDER BY t_utc",
        # Read a day either side so sessions spanning midnight have both their start and end.
        (to_utc_iso(day_start - timedelta(days=1)), to_utc_iso(day_end + timedelta(days=1))),
    ).fetchall()
    screen_s, unlocks, per_app = 0.0, 0, defaultdict(float)
    screen_on = app_since = None
    app = None

    def clipped(s, e):
        return max(0.0, (min(e, day_end) - max(s, day_start)).total_seconds())

    def close_app(t):
        nonlocal app, app_since
        if app is not None:
            per_app[app] += clipped(app_since, t)
        app = app_since = None

    for r in rows:
        t, event = datetime.fromisoformat(r["t_utc"]), r["event"]
        if event == "screen_on":
            screen_on = screen_on or t
        elif event in ("screen_off", "shutdown"):
            if screen_on is not None:
                screen_s += clipped(screen_on, t)
            screen_on = None
            close_app(t)
        elif event == "app_fg" and r["app"] != app:
            close_app(t)
            app, app_since = r["app"], t
        elif event == "app_bg" and r["app"] == app:
            close_app(t)
        elif event == "unlock" and day_start <= t < day_end:
            unlocks += 1
    return screen_s, unlocks, {a: s for a, s in per_app.items() if s > 0}


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

    out.phone_screen_s, out.phone_unlocks, phone_apps = _phone_usage(conn, day_start, day_end)
    out.phone_top_apps = sorted(phone_apps.items(), key=lambda x: -x[1])[:top_n]

    out.events_today = _calendar(conn, day_start, day_end, tz)
    out.events_tomorrow = _calendar(conn, day_end, day_end + timedelta(days=1), tz)

    mood = conn.execute("SELECT mood, note FROM mood_log WHERE day = ?", (day.isoformat(),)).fetchone()
    if mood:
        out.mood, out.mood_note = mood["mood"], mood["note"]
    return out
