import sqlite3
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS aw_event (
    bucket      TEXT    NOT NULL,
    event_id    INTEGER NOT NULL,
    kind        TEXT    NOT NULL,  -- window | afk | web
    start_utc   TEXT    NOT NULL,
    duration_s  REAL    NOT NULL,
    app         TEXT,
    title       TEXT,
    url         TEXT,
    afk         INTEGER,
    PRIMARY KEY (bucket, event_id)
);
CREATE INDEX IF NOT EXISTS aw_event_start ON aw_event (start_utc);

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
