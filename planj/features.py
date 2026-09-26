"""Turns raw device data into the daily numbers a forecast can learn from."""

import json
import sqlite3
from collections import defaultdict
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from planj import timeline
from planj.db import to_utc_iso

# Substring rules, first match wins. Names are lowercased before matching.
PC_CATEGORIES = {
    "coding": ("code.exe", "windowsterminal", "powershell", "cmd.exe", "idea", "pycharm", "studio"),
    "browsing": ("msedge", "chrome", "firefox", "brave", "opera"),
    "gaming": ("rcclient", "leagueclient", "league of legends", "valorant", "steam", "riot"),
    "chat": ("whatsapp", "discord", "telegram", "wechat", "slack"),
    "media": ("vlc", "spotify", "netflix", "potplayer", "mpc"),
}
PHONE_CATEGORIES = {
    "home": ("launcher", "systemui"),
    "social": ("instagram", "xingin.xhs", "twitter", "linkedin", "facebook", "xiaohongshu", "threads"),
    "chat": ("whatsapp", "telegram", "wechat", "messenger", "discord"),
    "video": ("youtube", "tiktok", "bilibili", "netflix", "douyin"),
    "shopping": ("shopee", "pinduoduo", "lazada", "taobao", "amazon"),
    "trading": ("tradingview", "binance", "metatrader", "moomoo", "webull"),
}

# Quick tags offered by the phone app; each becomes a 0/1 feature on logged days.
TAGS = ("study", "work", "exercise", "social", "family", "sick", "tired", "trading", "anime", "gaming", "travel", "alone")

LATE_NIGHT = (time(0, 0), time(5, 0))
MIN_QUIET_HOURS = 2.0
RAIN_PROB_THRESHOLD = 50
# A notification lighting the screen for a few seconds is not being awake, and such blips
# otherwise chop a night's sleep into pieces.
BRIEF_USE = timedelta(minutes=3)


def categorise(name: str, rules: dict[str, tuple[str, ...]]) -> str:
    low = name.lower()
    for category, needles in rules.items():
        if any(n in low for n in needles):
            return category
    return "other"


def _hours(seconds: float) -> float:
    return round(seconds / 3600, 3)


def _local_hour(moment: datetime, tz: ZoneInfo) -> float:
    local = moment.astimezone(tz)
    return round(local.hour + local.minute / 60, 2)


def _overlap_hours(intervals, start: datetime, end: datetime) -> float:
    total = 0.0
    for s, e in intervals:
        lo, hi = max(s, start), min(e, end)
        if hi > lo:
            total += (hi - lo).total_seconds()
    return _hours(total)


def _by_category(spans, rules, tz) -> dict[str, float]:
    per_category: dict[str, float] = defaultdict(float)
    for s, e, name in spans:
        per_category[categorise(name, rules)] += (e - s).total_seconds()
    return {c: _hours(v) for c, v in per_category.items()}


def estimate_quiet(conn, day: date, tz: ZoneInfo) -> dict[str, float]:
    """The longest device-free gap overnight. Not sleep: a quiet evening in front of the TV
    looks the same. Charging through the gap, and an alarm set for its end, make sleep likelier."""
    day_start = datetime.combine(day, time.min, tz)
    window_start, window_end = day_start - timedelta(hours=6), day_start + timedelta(hours=14)
    used = [(s, e) for s, e, _ in timeline.pc_active(conn, window_start, window_end)]
    used += timeline.phone_screen(conn, window_start, window_end)
    awake = timeline.merge([(s, e) for s, e in used if e - s >= BRIEF_USE])
    if len(awake) < 2:
        return {}

    gap_start, gap_end, longest = None, None, timedelta(0)
    for (_, end_of_use), (next_use, _) in zip(awake, awake[1:]):
        if next_use - end_of_use > longest:
            gap_start, gap_end, longest = end_of_use, next_use, next_use - end_of_use
    hours = longest.total_seconds() / 3600
    if hours < MIN_QUIET_HOURS:
        return {}
    out = {
        "quiet_h": round(hours, 2),
        "quiet_start_hour": _local_hour(gap_start, tz),
        "quiet_end_hour": _local_hour(gap_end, tz),
    }
    # "On charge" if plugged in during (or just before) the gap. Phones report "unplugged" once
    # the battery is full in the small hours, which says nothing about the person.
    plugged = conn.execute(
        "SELECT 1 FROM phone_event WHERE event = 'charging_on' AND t_utc >= ? AND t_utc <= ? LIMIT 1",
        (to_utc_iso(gap_start - timedelta(hours=2)), to_utc_iso(gap_end)),
    ).fetchone()
    if plugged:
        out["quiet_charging"] = 1.0
    else:
        last = conn.execute(
            "SELECT event FROM phone_event WHERE event IN ('charging_on', 'charging_off') AND t_utc <= ? "
            "ORDER BY t_utc DESC LIMIT 1",
            (to_utc_iso(gap_start),),
        ).fetchone()
        if last:
            out["quiet_charging"] = 1.0 if last["event"] == "charging_on" else 0.0
    alarm = conn.execute(
        "SELECT app FROM phone_event WHERE event = 'next_alarm' AND app != '' AND t_utc <= ? ORDER BY t_utc DESC LIMIT 1",
        (to_utc_iso(gap_start),),
    ).fetchone()
    if alarm:
        try:
            when = datetime.fromisoformat(alarm["app"].replace("Z", "+00:00"))
            if gap_start <= when <= gap_end + timedelta(hours=2):
                out["alarm_hour"] = _local_hour(when, tz)
        except ValueError:
            pass
    return out


def compute(conn: sqlite3.Connection, day: date, tz: ZoneInfo) -> dict[str, float]:
    day_start = datetime.combine(day, time.min, tz)
    day_end = day_start + timedelta(days=1)
    night_start = datetime.combine(day, LATE_NIGHT[0], tz)
    night_end = datetime.combine(day, LATE_NIGHT[1], tz)
    out: dict[str, float] = {}

    pc = timeline.pc_active(conn, day_start, day_end)
    pc_intervals = [(s, e) for s, e, _ in pc]
    if pc:
        out["pc_active_h"] = _hours(timeline.total_seconds(pc_intervals))
        out["pc_first_hour"] = _local_hour(pc[0][0], tz)
        out["pc_last_hour"] = _local_hour(max(e for _, e, _ in pc), tz)
        out["pc_late_night_h"] = _overlap_hours(pc_intervals, night_start, night_end)
        for category, hours in _by_category(pc, PC_CATEGORIES, tz).items():
            out[f"pc_{category}_h"] = hours

    screen = timeline.phone_screen(conn, day_start, day_end)
    apps = timeline.phone_apps(conn, day_start, day_end)
    if screen or apps:
        out["phone_screen_h"] = _hours(timeline.total_seconds(screen))
        out["phone_unlocks"] = timeline.phone_unlocks(conn, day_start, day_end)
        out["phone_late_night_h"] = _overlap_hours(screen, night_start, night_end)
        if screen:
            out["phone_first_hour"] = _local_hour(screen[0][0], tz)
            out["phone_last_hour"] = _local_hour(max(e for _, e in screen), tz)
        for category, hours in _by_category(apps, PHONE_CATEGORIES, tz).items():
            out[f"phone_{category}_h"] = hours

    out.update(estimate_quiet(conn, day, tz))

    weather = conn.execute(
        "SELECT COUNT(*) FILTER (WHERE precip_prob >= ?), MAX(temperature_c) "
        "FROM weather_hourly WHERE hour_local LIKE ?",
        (RAIN_PROB_THRESHOLD, f"{day.isoformat()}T%"),
    ).fetchone()
    if weather[1] is not None:
        out["rain_hours"] = weather[0]
        out["temp_max_c"] = weather[1]

    out["events_today"] = conn.execute(
        "SELECT COUNT(*) FROM calendar_event WHERE start_utc < ? AND end_utc > ?",
        (to_utc_iso(day_end), to_utc_iso(day_start)),
    ).fetchone()[0]
    out["events_tomorrow"] = conn.execute(
        "SELECT COUNT(*) FROM calendar_event WHERE start_utc < ? AND end_utc > ?",
        (to_utc_iso(day_end + timedelta(days=1)), to_utc_iso(day_end)),
    ).fetchone()[0]

    # Premium: per-topic minutes inside deeply tracked apps, summarised on the phone.
    for r in conn.execute(
        "SELECT detail FROM phone_event WHERE event = 'content_topic' AND detail LIKE ?",
        (f'%"day":"{day.isoformat()}"%',),
    ):
        d = json.loads(r["detail"])
        out[f"content_{d['topic']}_h"] = round(out.get(f"content_{d['topic']}_h", 0.0) + d["ms"] / 3600000, 3)
        out["content_actions"] = out.get("content_actions", 0.0) + d.get("actions", 0)

    out["weekday"] = day.weekday()
    mood = conn.execute("SELECT mood, tags FROM mood_log WHERE day = ?", (day.isoformat(),)).fetchone()
    if mood:
        out["mood"] = mood["mood"]
        chosen = set((mood["tags"] or "").split(","))
        for tag in TAGS:
            out[f"tag_{tag}"] = 1.0 if tag in chosen else 0.0
    return out


def store(conn: sqlite3.Connection, day: date, values: dict[str, float]) -> int:
    conn.executemany(
        "INSERT OR REPLACE INTO day_feature VALUES (?, ?, ?)",
        [(day.isoformat(), name, float(v)) for name, v in values.items()],
    )
    conn.commit()
    return len(values)


def rebuild(conn: sqlite3.Connection, days: int, tz: ZoneInfo, today: date) -> dict[date, dict[str, float]]:
    out = {}
    for back in range(days):
        day = today - timedelta(days=back)
        values = compute(conn, day, tz)
        store(conn, day, values)
        out[day] = values
    return out


def _pearson(xs: list[float], ys: list[float]) -> float | None:
    n = len(xs)
    mean_x, mean_y = sum(xs) / n, sum(ys) / n
    dx = [x - mean_x for x in xs]
    dy = [y - mean_y for y in ys]
    denom = (sum(d * d for d in dx) ** 0.5) * (sum(d * d for d in dy) ** 0.5)
    if denom == 0:
        return None  # a feature that never varies says nothing
    return sum(a * b for a, b in zip(dx, dy)) / denom


def correlations(conn: sqlite3.Connection, min_days: int = 7) -> tuple[int, list[tuple[str, float, int]]]:
    """How each feature moves with mood. Returns (days with mood, ranked correlations)."""
    moods = {r["day"]: r["mood"] for r in conn.execute("SELECT day, mood FROM mood_log")}
    if len(moods) < min_days:
        return len(moods), []

    values: dict[str, dict[str, float]] = defaultdict(dict)
    for r in conn.execute("SELECT day, name, value FROM day_feature"):
        if r["day"] in moods and r["name"] != "mood":
            values[r["name"]][r["day"]] = r["value"]

    ranked = []
    for name, by_day in values.items():
        days = sorted(by_day)
        if len(days) < min_days:
            continue
        r = _pearson([by_day[d] for d in days], [float(moods[d]) for d in days])
        if r is not None:
            ranked.append((name, round(r, 3), len(days)))
    ranked.sort(key=lambda x: -abs(x[1]))
    return len(moods), ranked
