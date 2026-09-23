import argparse
import sys
from datetime import date, datetime, timedelta
from urllib.error import URLError

from planj import config, features
from planj.db import connect, now_utc_iso
from planj.sources import calendar, phone, tracker, weather
from planj.summary import summarize


def _fmt_dur(seconds: float) -> str:
    m = int(seconds // 60)
    return f"{m // 60}h {m % 60:02d}m"


def _today() -> date:
    return datetime.now(config.TZ).date()


def import_local(conn, days: int) -> tuple[int, int, int]:
    """Reads what the tracker and the phone already left on disk. No network."""
    spans = tracker.sync(conn, config.TRACKER_DIR, _today() - timedelta(days=days)) if config.TRACKER_DIR else 0
    events, moods = phone.sync(conn, config.PHONE_DIRS)
    return spans, events, moods


def cmd_sync(args, conn) -> int:
    failed = False
    spans, events, moods = import_local(conn, args.days)
    if config.TRACKER_DIR:
        print(f"PC tracker: {spans} spans")
    else:
        print("PC tracker: no data folder yet — is planj-tracker installed on Windows?")
    print(f"Phone: {events} new events, {moods} mood entries")
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
    # Import first, so the summary always reflects what the devices have delivered.
    import_local(conn, args.days)
    s = summarize(conn, args.day or _today(), config.TZ)
    print(f"{s.day}  ({config.TZ.key})")
    if s.active_s:
        print(f"Active on PC: {_fmt_dur(s.active_s)}  (first {s.first_active:%H:%M}, last {s.last_active:%H:%M})")
        print("Top apps:")
        for app, secs in s.top_apps:
            print(f"  {app:<28} {_fmt_dur(secs)}")
    else:
        print("Active on PC: no data (run `planj sync`)")
    if s.phone_screen_s or s.phone_unlocks:
        print(f"Phone: {_fmt_dur(s.phone_screen_s)} screen on, {s.phone_unlocks} unlocks")
        for app, secs in s.phone_top_apps:
            print(f"  {app:<40} {_fmt_dur(secs)}")
    else:
        print("Phone: no data (export from the planj app, then `planj sync`)")
    print("Rain likely at: " + (", ".join(s.rainy_hours) if s.rainy_hours else "no hours ≥50%"))
    for label, events in (("Calendar today", s.events_today), ("Tomorrow", s.events_tomorrow)):
        print(f"{label}: " + ("; ".join(f"{t} {name}" for t, name in events) if events else "nothing"))
    if s.mood:
        print(f"Mood: {s.mood}/5" + (f" — {s.mood_note}" if s.mood_note else ""))
    else:
        print("Mood: not logged (run `planj log <1-5>`)")
    return 0


FEATURE_COLUMNS = [
    ("sleep_h", "sleep"),
    ("pc_active_h", "pc"),
    ("phone_screen_h", "phone"),
    ("phone_unlocks", "unlocks"),
    ("phone_late_night_h", "late"),
    ("mood", "mood"),
]


def cmd_features(args, conn) -> int:
    import_local(conn, args.days)
    built = features.rebuild(conn, args.days, config.TZ, _today())

    print("day          " + "".join(f"{label:>9}" for _, label in FEATURE_COLUMNS))
    for day in sorted(built, reverse=True):
        values = built[day]
        cells = "".join(
            f"{values[name]:>9.1f}" if name in values else f"{'·':>9}"
            for name, _ in FEATURE_COLUMNS
        )
        print(f"{day}  {cells}")

    extra = sorted({k for v in built.values() for k in v} - {c[0] for c in FEATURE_COLUMNS})
    print(f"\n{len(extra)} more features stored per day: {', '.join(extra)}")
    return 0


def cmd_correlate(args, conn) -> int:
    import_local(conn, args.days)
    features.rebuild(conn, args.days, config.TZ, _today())
    logged, ranked = features.correlations(conn, args.min_days)
    if not ranked:
        print(f"Not enough mood entries yet: {logged} logged, {args.min_days} needed.")
        print("Tap your mood in the phone app each evening — that is what the forecast learns from.")
        return 0
    print(f"How each feature moved with mood, across {logged} logged days:\n")
    for name, r, days in ranked[: args.top]:
        bar = "█" * round(abs(r) * 20)
        print(f"  {name:<22} {r:+.2f} {bar:<20} ({days}d)")
    print("\nCorrelation is not cause, and early numbers move a lot as days are added.")
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

    tp = sub.add_parser("today", help="summarise a day (imports new device data first)")
    tp.add_argument("--day", type=date.fromisoformat)
    tp.add_argument("--days", type=int, default=2, help="how far back to re-read device files")
    tp.set_defaults(func=cmd_today)

    fp = sub.add_parser("features", help="daily numbers the forecast will learn from")
    fp.add_argument("--days", type=int, default=14)
    fp.set_defaults(func=cmd_features)

    cp = sub.add_parser("correlate", help="which features move with your mood")
    cp.add_argument("--days", type=int, default=60)
    cp.add_argument("--min-days", type=int, default=7)
    cp.add_argument("--top", type=int, default=15)
    cp.set_defaults(func=cmd_correlate)

    args = p.parse_args(argv)
    with connect(config.DB_PATH) as conn:
        return args.func(args, conn)


if __name__ == "__main__":
    sys.exit(main())
