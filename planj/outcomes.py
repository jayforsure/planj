"""Forecasts about tomorrow that label themselves the morning after.

An outcome is a yes/no question about a day whose answer the devices record without anyone
being asked. A lever is a yes/no condition known the evening before. The forecast for an
outcome is its rate in the user's own history, narrowed by the lever that split that history
most sharply. Every forecast is stored before the day and scored after, so the track record
is real.
"""

import sqlite3
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, timedelta
from statistics import median

from planj.db import now_utc_iso

Features = dict[str, float]

MIN_SIDE = 4        # days on each side of a lever before it may be used
MIN_HISTORY = 5     # days of history before any forecast is made


def _off_by_1am(f: Features) -> bool | None:
    start = f.get("quiet_start_hour")
    if start is None:
        return None
    return start >= 20 or start <= 1.0  # the longest device-free stretch began by 01:00


def _quiet_7h(f: Features) -> bool | None:
    q = f.get("quiet_h")
    return None if q is None else q >= 7


def _heavy_screen(f: Features, hist: list[Features]) -> bool | None:
    if "phone_screen_h" not in f:
        return None
    past = [h["phone_screen_h"] for h in hist if "phone_screen_h" in h]
    return f["phone_screen_h"] > median(past) if len(past) >= MIN_HISTORY else None


def _social_2h(f: Features) -> bool | None:
    return None if "phone_screen_h" not in f else f.get("phone_social_h", 0.0) > 2


@dataclass(frozen=True)
class Outcome:
    name: str
    question: str      # phrased for the evening before
    resolved: str      # phrased once known
    label: object      # fn(features_of_target_day, history) -> bool | None


OUTCOMES = [
    Outcome("off_by_1am", "Off all devices by 1am tonight", "off devices by 1am", lambda f, h: _off_by_1am(f)),
    Outcome("quiet_7h", "At least 7 hours device-free tonight", "7h+ device-free", lambda f, h: _quiet_7h(f)),
    Outcome("heavy_screen", "Tomorrow is a heavier-than-usual screen day", "heavy screen day", _heavy_screen),
    Outcome("social_2h", "Over 2 hours of social apps tomorrow", "2h+ social", lambda f, h: _social_2h(f)),
]


@dataclass(frozen=True)
class Lever:
    name: str
    when_true: str     # description of the condition holding on the evening of the forecast
    test: object       # fn(features_of_forecast_day) -> bool | None


def _median_split(key: str):
    def test(f: Features, hist: list[Features]) -> bool | None:
        if key not in f:
            return None
        past = [h[key] for h in hist if key in h]
        return f[key] > median(past) if len(past) >= MIN_HISTORY else None
    return test


LEVERS = [
    Lever("short_night", "after a night under 7h device-free", lambda f, h: None if "quiet_h" not in f else f["quiet_h"] < 7),
    Lever("late_phone", "after phone use between midnight and 5am", lambda f, h: None if "phone_screen_h" not in f else f.get("phone_late_night_h", 0) > 0.5),
    Lever("social_heavy", "on a day with over 2h of social apps", lambda f, h: None if "phone_screen_h" not in f else f.get("phone_social_h", 0) > 2),
    Lever("many_unlocks", "on a day with more unlocks than usual", _median_split("phone_unlocks")),
    Lever("pc_heavy", "on a day with more PC time than usual", _median_split("pc_active_h")),
    Lever("weekend_next", "when tomorrow is a weekend day", lambda f, h: f.get("weekday") in (4, 5)),  # Fri/Sat evening
]


@dataclass
class Forecast:
    outcome: str
    question: str
    prob: float
    base_rate: float
    n: int
    lever: str | None
    lever_text: str | None
    lever_side: bool | None
    side_k: int
    side_n: int


def has_data(f: Features) -> bool:
    return "phone_screen_h" in f or "pc_active_h" in f


def load_history(conn: sqlite3.Connection) -> dict[date, Features]:
    out: dict[date, Features] = defaultdict(dict)
    for r in conn.execute("SELECT day, name, value FROM day_feature"):
        out[date.fromisoformat(r["day"])][r["name"]] = r["value"]
    return {d: f for d, f in out.items() if has_data(f)}


def _pairs(history: dict[date, Features], before: date):
    """(evening features, next-day features) for consecutive days strictly before `before`."""
    for d, f in history.items():
        nxt = history.get(d + timedelta(days=1))
        if nxt is not None and d + timedelta(days=1) < before:
            yield d, f, nxt


def _earns_its_keep(sides: list[tuple[bool, bool]]) -> bool:
    """A lever is used only if, replaying history leaving each day out, narrowing by it would
    have scored better than the plain base rate. Small samples make sharp-looking splits
    that are only noise; this is what keeps them out of the forecast."""
    lever_err = base_err = 0.0
    for i, (s, y) in enumerate(sides):
        rest = [(ss, yy) for j, (ss, yy) in enumerate(sides) if j != i]
        same = [yy for ss, yy in rest if ss == s]
        p_lever = (sum(same) + 1) / (len(same) + 2)
        p_base = (sum(yy for _, yy in rest) + 1) / (len(rest) + 2)
        lever_err += (p_lever - y) ** 2
        base_err += (p_base - y) ** 2
    return lever_err < base_err


def forecast(history: dict[date, Features], evening: date) -> list[Forecast]:
    """Forecasts made on the evening of `evening` for the following day, from history before it."""
    today = history.get(evening)
    if today is None:
        return []
    past_days = [f for d, f in history.items() if d < evening]
    out = []
    for oc in OUTCOMES:
        rows = []
        for d, f, nxt in _pairs(history, evening):
            hist_before = [h for dd, h in history.items() if dd < d + timedelta(days=1)]
            y = oc.label(nxt, hist_before)
            if y is not None:
                rows.append((f, [h for dd, h in history.items() if dd < d], y))
        if len(rows) < MIN_HISTORY:
            continue
        k, n = sum(1 for _, _, y in rows if y), len(rows)
        base = k / n
        best = None
        for lv in LEVERS:
            side_today = lv.test(today, past_days)
            if side_today is None:
                continue
            sides = [(lv.test(f, hist), y) for f, hist, y in rows]
            sides = [(s, y) for s, y in sides if s is not None]
            split = {True: [0, 0], False: [0, 0]}
            for s, y in sides:
                split[s][1] += 1
                split[s][0] += int(y)
            if split[True][1] < MIN_SIDE or split[False][1] < MIN_SIDE:
                continue
            if not _earns_its_keep(sides):
                continue
            gap = abs(split[True][0] / split[True][1] - split[False][0] / split[False][1])
            if best is None or gap > best[0]:
                best = (gap, lv, side_today, split[side_today])
        if best:
            _, lv, side, (sk, sn) = best
            prob = (sk + 1) / (sn + 2)  # Laplace: small samples stay near the middle
            out.append(Forecast(oc.name, oc.question, prob, base, n, lv.name,
                                lv.when_true if side else "not " + lv.when_true, side, sk, sn))
        else:
            out.append(Forecast(oc.name, oc.question, (k + 1) / (n + 2), base, n, None, None, None, k, n))
    return out


def resolve(history: dict[date, Features], target: date, outcome_name: str) -> bool | None:
    f = history.get(target)
    if f is None:
        return None
    oc = next(o for o in OUTCOMES if o.name == outcome_name)
    return oc.label(f, [h for d, h in history.items() if d < target])


def store(conn: sqlite3.Connection, evening: date, forecasts: list[Forecast]) -> int:
    """Records forecasts for tomorrow; an existing one for the same target is left as made."""
    target = (evening + timedelta(days=1)).isoformat()
    rows = [(target, fc.outcome, evening.isoformat(), fc.prob, fc.base_rate, fc.n, fc.lever, fc.lever_text, now_utc_iso())
            for fc in forecasts]
    before = conn.total_changes
    conn.executemany(
        "INSERT OR IGNORE INTO forecast (target_day, outcome, made_on, prob, base_rate, n, lever, lever_text, made_at_utc) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        rows,
    )
    conn.commit()
    return conn.total_changes - before


def settle(conn: sqlite3.Connection, history: dict[date, Features]) -> int:
    """Fills in what actually happened for every unresolved forecast whose day has data."""
    settled = 0
    for r in conn.execute("SELECT target_day, outcome FROM forecast WHERE actual IS NULL").fetchall():
        y = resolve(history, date.fromisoformat(r["target_day"]), r["outcome"])
        if y is not None:
            conn.execute("UPDATE forecast SET actual = ? WHERE target_day = ? AND outcome = ?",
                         (int(y), r["target_day"], r["outcome"]))
            settled += 1
    conn.commit()
    return settled


@dataclass
class Record:
    n: int = 0
    hits: int = 0
    base_hits: int = 0
    brier: float = 0.0
    base_brier: float = 0.0

    def add(self, prob: float, base: float, y: bool):
        self.n += 1
        self.hits += int((prob >= 0.5) == y)
        self.base_hits += int((base >= 0.5) == y)
        self.brier += (prob - y) ** 2
        self.base_brier += (base - y) ** 2


def track_record(conn: sqlite3.Connection) -> dict[str, Record]:
    out: dict[str, Record] = defaultdict(Record)
    for r in conn.execute("SELECT outcome, prob, base_rate, actual FROM forecast WHERE actual IS NOT NULL"):
        out[r["outcome"]].add(r["prob"], r["base_rate"], bool(r["actual"]))
    return dict(out)


def backtest(history: dict[date, Features]) -> dict[str, Record]:
    """What the track record would have been, forecasting each evening from only the days before it."""
    out: dict[str, Record] = defaultdict(Record)
    for evening in sorted(history):
        target = evening + timedelta(days=1)
        if target not in history:
            continue
        for fc in forecast(history, evening):
            y = resolve(history, target, fc.outcome)
            if y is not None:
                out[fc.outcome].add(fc.prob, fc.base_rate, y)
    return dict(out)
