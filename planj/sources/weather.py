import json
import sqlite3
from urllib.parse import urlencode
from urllib.request import urlopen

from planj.db import now_utc_iso

OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast"
HOURLY_FIELDS = "temperature_2m,precipitation,precipitation_probability,weather_code"


def sync(conn: sqlite3.Connection, lat: float, lon: float) -> int:
    params = urlencode({
        "latitude": lat,
        "longitude": lon,
        "hourly": HOURLY_FIELDS,
        "timezone": "auto",
        "past_days": 2,
        "forecast_days": 3,
    })
    with urlopen(f"{OPEN_METEO_URL}?{params}", timeout=10) as resp:
        hourly = json.load(resp)["hourly"]

    fetched = now_utc_iso()
    rows = [
        (t, temp, precip, prob, code, fetched)
        for t, temp, precip, prob, code in zip(
            hourly["time"],
            hourly["temperature_2m"],
            hourly["precipitation"],
            hourly["precipitation_probability"],
            hourly["weather_code"],
        )
    ]
    conn.executemany("INSERT OR REPLACE INTO weather_hourly VALUES (?, ?, ?, ?, ?, ?)", rows)
    conn.commit()
    return len(rows)
