import argparse
import json
import sys
from datetime import date, datetime, timedelta
from urllib.error import URLError

from planj import account, config, features, outcomes
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
        "INSERT OR REPLACE INTO mood_log (day, mood, note, logged_at_utc) VALUES (?, ?, ?, ?)",
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
    ("quiet_h", "quiet"),
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


def cmd_odds(args, conn) -> int:
    import_local(conn, args.days)
    features.rebuild(conn, args.days, config.TZ, _today())
    history = outcomes.load_history(conn)
    settled = outcomes.settle(conn, history)

    today = _today()
    made = outcomes.forecast(history, today)
    stored = outcomes.store(conn, today, made)
    print(f"Tomorrow ({today + timedelta(days=1):%a %d %b}), from your last {len(history)} days"
          + (f" · {stored} new forecasts recorded" if stored else " · already recorded tonight") + "\n")
    if not made:
        print(f"  Not enough history yet ({len(history)} days with data, {outcomes.MIN_HISTORY} needed).")
    for fc in made:
        bar = "█" * round(fc.prob * 20)
        print(f"  {fc.prob:4.0%}  {fc.question}")
        print(f"        {bar:<20} base {fc.base_rate:.0%} of {fc.n} days"
              + (f" · {fc.side_k} of {fc.side_n} {fc.lever_text}" if fc.lever else ""))

    live = outcomes.track_record(conn)
    back = outcomes.backtest(history)
    print(f"\nTrack record" + (f" · {settled} forecasts settled this run" if settled else "") + ":")
    for oc in outcomes.OUTCOMES:
        r = live.get(oc.name)
        b = back.get(oc.name)
        line = f"  {oc.resolved:<22}"
        line += f" live: {r.hits}/{r.n} right" if r and r.n else " live: none settled yet"
        if b and b.n:
            line += (f" · walk-forward: {b.hits}/{b.n} right vs {b.base_hits}/{b.n} guessing your average"
                     f" · Brier {b.brier / b.n:.2f} vs {b.base_brier / b.n:.2f}")
        print(line)
    print("\nWalk-forward = what these forecasts would have scored, made each evening from only the days before it.")
    return 0


def _planj_root():
    root = config.WIN_HOME / "AppData" / "Local" / "planj" if config.WIN_HOME else config.DB_PATH.parent
    root.mkdir(parents=True, exist_ok=True)
    return root


def _session_path():
    return _planj_root() / "account.json"


def _load_session():
    import base64
    try:
        d = json.loads(_session_path().read_text(encoding="utf-8"))
        return account.Session(d["email"], d["token"], True), base64.b64decode(d["account_key"])
    except (OSError, KeyError, ValueError):
        return None, None


def _save_session(s, account_key: bytes) -> None:
    import base64
    _session_path().write_text(json.dumps({"email": s.email, "token": s.token,
                                           "account_key": base64.b64encode(account_key).decode("ascii")}) + "\n",
                               encoding="utf-8")
    (_planj_root() / "pairing.txt").write_text(account.pairing_code(account_key) + "\n", encoding="utf-8")


def _device_name() -> str:
    import platform
    return (platform.node() or "PC")[:40]


def _prompt_password(label: str) -> str:
    import getpass
    return getpass.getpass(label)


def _finish_signin(s, account_key: bytes) -> int:
    _save_session(s, account_key)
    print(f"Signed in as {s.email}. This PC shares one encrypted record with every device on this account; "
          f"the tracker picks it up within five minutes.")
    return 0


def cmd_signup(args, conn) -> int:
    email = args.email or input("Email: ")
    password = _prompt_password("Choose a password (never stored, never sent): ")
    if len(password) < 8:
        print("Use at least 8 characters.", file=sys.stderr)
        return 1
    if _prompt_password("Type it again: ") != password:
        print("The passwords do not match.", file=sys.stderr)
        return 1
    try:
        account.register(email, password)
        print(f"A 6-digit code was sent to {account.normalise_email(email)}.")
        s = account.verify(email, input("Code: "), _device_name())
        if s.has_keybox:
            key = account.open_keybox(s, password)
        else:
            key, recovery = account.new_account_key(), account.new_recovery_code()
            account.create_keybox(s, password, key, recovery)
            print("\nYour recovery code — write it down somewhere safe. It is the only way to keep your data\n"
                  "if you forget your password, and it is never shown again:\n\n        " + recovery + "\n")
    except account.AccountError as exc:
        print(exc, file=sys.stderr)
        return 1
    return _finish_signin(s, key)


def cmd_signin(args, conn) -> int:
    email = args.email or input("Email: ")
    password = _prompt_password("Password (never stored, never sent): ")
    try:
        try:
            s = account.login(email, password, _device_name())
        except account.AccountError as exc:
            if exc.status != 403:
                raise
            account.resend(email)
            print(f"Your email is not verified yet; a new code was sent to {account.normalise_email(email)}.")
            s = account.verify(email, input("Code: "), _device_name())
        key = account.open_keybox(s, password)
        if key is None:
            key, recovery = account.new_account_key(), account.new_recovery_code()
            account.create_keybox(s, password, key, recovery)
            print("\nThis account had no data key yet, so this PC made one. Your recovery code, shown once:\n\n"
                  "        " + recovery + "\n")
    except (account.AccountError, ValueError) as exc:
        print(exc, file=sys.stderr)
        return 1
    return _finish_signin(s, key)


def cmd_forgot(args, conn) -> int:
    email = args.email or input("Email: ")
    try:
        account.forgot(email)
        print(f"If {account.normalise_email(email)} has an account, a reset code is on its way.")
        code = input("Code: ")
        password = _prompt_password("New password: ")
        if len(password) < 8 or _prompt_password("Type it again: ") != password:
            print("Passwords must match and have at least 8 characters.", file=sys.stderr)
            return 1
        s = account.reset(email, code, password, _device_name())
        key = None
        if s.has_keybox:
            print("\nYour data key is locked with the old password. Enter your recovery code to keep your\n"
                  "existing synced data, or press Enter to start a fresh key (other devices must sign in again).")
            recovery = input("Recovery code: ").strip()
            if recovery:
                key = account.open_keybox_with_recovery(s, recovery)
        if key is None:
            key, recovery = account.new_account_key(), account.new_recovery_code()
            print("\nNew recovery code, shown once:\n\n        " + recovery + "\n")
        else:
            recovery = account.new_recovery_code()
            print("\nData key recovered. New recovery code, shown once:\n\n        " + recovery + "\n")
        account.create_keybox(s, password, key, recovery)
    except (account.AccountError, ValueError) as exc:
        print(exc, file=sys.stderr)
        return 1
    return _finish_signin(s, key)


def cmd_account(args, conn) -> int:
    s, key = _load_session()
    if s is None:
        print("Not signed in. Run: planj signin  (or planj signup)")
        return 0
    try:
        info = account.me(s)
    except account.AccountError as exc:
        print(f"{s.email} — session problem: {exc}. Run: planj signin")
        return 1
    print(f"{info['email']} · account since {info['created'][:10]}")
    print("Devices:")
    for d in info["devices"]:
        print(f"  {'*' if d['this'] else ' '} {d['name']:<20} last seen {d['last_seen'][:16].replace('T', ' ')}")
    print("Recovery code on file: " + ("yes" if info.get("has_recovery") else "no"))
    return 0


def cmd_signout(args, conn) -> int:
    s, _ = _load_session()
    if s is None:
        print("Not signed in.")
        return 0
    try:
        account.logout(s)
    except account.AccountError:
        pass  # the token is dropped locally either way
    _session_path().unlink(missing_ok=True)
    (_planj_root() / "pairing.txt").unlink(missing_ok=True)
    print("Signed out. Recorded data stays on this PC; the tracker stops syncing.")
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

    su = sub.add_parser("signup", help="create your planj account on this PC")
    su.add_argument("--email")
    su.set_defaults(func=cmd_signup)

    si = sub.add_parser("signin", help="sign this PC in with your email and password")
    si.add_argument("--email")
    si.set_defaults(func=cmd_signin)

    fp2 = sub.add_parser("forgot", help="reset your password with a code sent by email")
    fp2.add_argument("--email")
    fp2.set_defaults(func=cmd_forgot)

    ap = sub.add_parser("account", help="who this PC is signed in as, and which devices share the account")
    ap.set_defaults(func=cmd_account)

    so = sub.add_parser("signout", help="sign this PC out")
    so.set_defaults(func=cmd_signout)

    op = sub.add_parser("odds", help="tomorrow's forecasts, recorded now and scored after")
    op.add_argument("--days", type=int, default=45)
    op.set_defaults(func=cmd_odds)

    args = p.parse_args(argv)
    with connect(config.DB_PATH) as conn:
        return args.func(args, conn)


if __name__ == "__main__":
    sys.exit(main())
