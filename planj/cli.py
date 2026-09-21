import argparse
import sys
from datetime import date, datetime
from urllib.error import URLError

from planj import config
from planj.db import connect, now_utc_iso
from planj.sources import activitywatch, calendar, weather
from planj.summary import summarize


def _fmt_dur(seconds: float) -> str:
    m = int(seconds // 60)
    return f"{m // 60}h {m % 60:02d}m"


def _today() -> date:
    return datetime.now(config.TZ).date()


def cmd_sync(args, conn) -> int:
    failed = False
    try:
        counts = activitywatch.sync(conn, config.AW_URL, args.days)
        print("ActivityWatch: " + (", ".join(f"{n} {k}" for k, n in counts.items()) or "no buckets found"))
    except URLError as exc:
        failed = True
        print(f"ActivityWatch unreachable at {config.AW_URL} ({exc.reason}). Is it running on Windows?", file=sys.stderr)
    try:
        print(f"Weather: {weather.sync(conn, config.LAT, config.LON)} hourly rows")
    except URLError as exc:
        failed = True
        print(f"Weather fetch failed ({exc.reason})", file=sys.stderr)
    if config.ICS_URL:
        try:
            print(f"Calendar: {calendar.sync(conn, config.ICS_URL, config.TZ)} events")
        except URLError as exc:
            failed = True
            print(f"Calendar fetch failed ({exc.reason}) — check PLANJ_ICS_URL", file=sys.stderr)
    else:
        print("Calendar: skipped (PLANJ_ICS_URL not set)")
    return 1 if failed else 0


def cmd_log(args, conn) -> int:
    day = args.day or _today()
    conn.execute(
        "INSERT OR REPLACE INTO mood_log VALUES (?, ?, ?, ?)",
        (day.isoformat(), args.mood, args.note, now_utc_iso()),
    )
    conn.commit()
    print(f"Logged mood {args.mood}/5 for {day}")
    return 0


def cmd_today(args, conn) -> int:
    s = summarize(conn, args.day or _today(), config.TZ)
    print(f"{s.day}  ({config.TZ.key})")
    if s.active_s:
        print(f"Active on PC: {_fmt_dur(s.active_s)}  (first {s.first_active:%H:%M}, last {s.last_active:%H:%M})")
        print("Top apps:")
        for app, secs in s.top_apps:
            print(f"  {app:<28} {_fmt_dur(secs)}")
    else:
        print("Active on PC: no data (run `planj sync`)")
    print("Rain likely at: " + (", ".join(s.rainy_hours) if s.rainy_hours else "no hours ≥50%"))
    for label, events in (("Calendar today", s.events_today), ("Tomorrow", s.events_tomorrow)):
        print(f"{label}: " + ("; ".join(f"{t} {name}" for t, name in events) if events else "nothing"))
    if s.mood:
        print(f"Mood: {s.mood}/5" + (f" — {s.mood_note}" if s.mood_note else ""))
    else:
        print("Mood: not logged (run `planj log <1-5>`)")
    return 0


def _mood(value: str) -> int:
    n = int(value)
    if not 1 <= n <= 5:
        raise argparse.ArgumentTypeError("mood must be 1-5")
    return n


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="planj")
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("sync", help="pull ActivityWatch events and weather")
    sp.add_argument("--days", type=int, default=2)
    sp.set_defaults(func=cmd_sync)

    lp = sub.add_parser("log", help="log how you felt today")
    lp.add_argument("mood", type=_mood)
    lp.add_argument("--note")
    lp.add_argument("--day", type=date.fromisoformat)
    lp.set_defaults(func=cmd_log)

    tp = sub.add_parser("today", help="summarise a day")
    tp.add_argument("--day", type=date.fromisoformat)
    tp.set_defaults(func=cmd_today)

    args = p.parse_args(argv)
    with connect(config.DB_PATH) as conn:
        return args.func(args, conn)


if __name__ == "__main__":
    sys.exit(main())
