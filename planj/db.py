import sqlite3
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS activity_span (
    start_utc TEXT    PRIMARY KEY,
    end_utc   TEXT    NOT NULL,
    app       TEXT    NOT NULL,
    idle      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS phone_event (
    t_utc TEXT NOT NULL,
    event TEXT NOT NULL,  -- app_fg | app_bg | screen_on | screen_off | unlock | shutdown | startup
    app   TEXT NOT NULL,  -- package name for app_* events, '' otherwise
    PRIMARY KEY (t_utc, event, app)
);

CREATE TABLE IF NOT EXISTS weather_hourly (
    hour_local     TEXT PRIMARY KEY,
    temperature_c  REAL,
    precip_mm      REAL,
    precip_prob    INTEGER,
    weather_code   INTEGER,
    fetched_at_utc TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS calendar_event (
    uid       TEXT    NOT NULL,
    start_utc TEXT    NOT NULL,
    end_utc   TEXT    NOT NULL,
    summary   TEXT,
    all_day   INTEGER NOT NULL,
    PRIMARY KEY (uid, start_utc)
);
CREATE INDEX IF NOT EXISTS calendar_event_start ON calendar_event (start_utc);

CREATE TABLE IF NOT EXISTS day_feature (
    day   TEXT NOT NULL,
    name  TEXT NOT NULL,
    value REAL NOT NULL,
    PRIMARY KEY (day, name)
);

CREATE TABLE IF NOT EXISTS mood_log (
    day           TEXT PRIMARY KEY,
    mood          INTEGER NOT NULL CHECK (mood BETWEEN 1 AND 5),
    note          TEXT,
    logged_at_utc TEXT NOT NULL
);
"""


def connect(path: Path | str) -> sqlite3.Connection:
    if str(path) != ":memory:":
        Path(path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.executescript(SCHEMA)
    return conn


def to_utc_iso(value: datetime | str) -> str:
    # Fixed microsecond precision keeps ISO strings lexically sortable in SQL range queries.
    dt = datetime.fromisoformat(value) if isinstance(value, str) else value
    return dt.astimezone(timezone.utc).isoformat(timespec="microseconds")


def now_utc_iso() -> str:
    return to_utc_iso(datetime.now(timezone.utc))
