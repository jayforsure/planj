from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from planj import features
from planj.db import connect, to_utc_iso

KL = ZoneInfo("Asia/Kuala_Lumpur")
DAY = date(2026, 9, 21)


def kl(hour, minute=0, day=DAY):
    return datetime(day.year, day.month, day.day, hour, minute, tzinfo=KL)


def add_pc(conn, start, end, app="Code.exe", idle=0):
    conn.execute("INSERT OR REPLACE INTO activity_span VALUES (?, ?, ?, ?)",
                 (to_utc_iso(start), to_utc_iso(end), app, idle))


def add_screen(conn, start, end, app="com.instagram.android"):
    rows = [
        (to_utc_iso(start), "screen_on", ""),
        (to_utc_iso(start), "app_fg", app),
        (to_utc_iso(end), "app_bg", app),
        (to_utc_iso(end), "screen_off", ""),
    ]
    conn.executemany("INSERT OR REPLACE INTO phone_event VALUES (?, ?, ?)", rows)


def test_categorise_falls_back_to_other():
    assert features.categorise("msedge.exe", features.PC_CATEGORIES) == "browsing"
    assert features.categorise("RCClient.exe", features.PC_CATEGORIES) == "gaming"
    assert features.categorise("com.xingin.xhs", features.PHONE_CATEGORIES) == "social"
    assert features.categorise("com.hihonor.android.launcher", features.PHONE_CATEGORIES) == "home"
    assert features.categorise("some.unknown.app", features.PHONE_CATEGORIES) == "other"


def test_day_features_cover_both_devices():
    conn = connect(":memory:")
    add_pc(conn, kl(9), kl(11))                      # 2h coding
    add_pc(conn, kl(13), kl(14), app="msedge.exe")   # 1h browsing
    add_pc(conn, kl(2), kl(3), app="msedge.exe")     # 1h late night
    add_screen(conn, kl(20), kl(21))                 # 1h phone, social
    conn.execute("INSERT INTO phone_event VALUES (?, 'unlock', '')", (to_utc_iso(kl(20)),))

    f = features.compute(conn, DAY, KL)
    assert f["pc_active_h"] == 4.0
    assert f["pc_coding_h"] == 2.0 and f["pc_browsing_h"] == 2.0
    assert f["pc_late_night_h"] == 1.0
    assert f["pc_first_hour"] == 2.0 and f["pc_last_hour"] == 14.0
    assert f["phone_screen_h"] == 1.0 and f["phone_social_h"] == 1.0
    assert f["phone_unlocks"] == 1
    assert f["weekday"] == 0  # 21 Sep 2026 is a Monday


def test_quiet_is_the_longest_overnight_gap():
    conn = connect(":memory:")
    prev = DAY - timedelta(days=1)
    add_pc(conn, kl(22, 0, prev), kl(23, 30, prev))  # last use before bed
    add_screen(conn, kl(7, 30), kl(8, 0))            # first phone use in the morning
    add_pc(conn, kl(9), kl(12))

    f = features.compute(conn, DAY, KL)
    assert f["quiet_h"] == 8.0
    assert f["quiet_start_hour"] == 23.5
    assert f["quiet_end_hour"] == 7.5


def test_night_notifications_do_not_break_quiet():
    conn = connect(":memory:")
    prev = DAY - timedelta(days=1)
    add_pc(conn, kl(22, 0, prev), kl(23, 30, prev))
    # a 12-second screen wake at 03:00, the kind a notification causes
    add_screen(conn, kl(3, 0), kl(3, 0) + timedelta(seconds=12))
    add_screen(conn, kl(7, 30), kl(8, 0))

    f = features.compute(conn, DAY, KL)
    assert f["quiet_h"] == 8.0  # not split into 3.5h + 4.5h


def test_short_gaps_are_not_quiet():
    conn = connect(":memory:")
    add_pc(conn, kl(9), kl(10))
    add_pc(conn, kl(11), kl(12))  # a one-hour break is not sleep
    assert "quiet_h" not in features.compute(conn, DAY, KL)


def test_correlations_need_enough_days_then_rank_by_strength():
    conn = connect(":memory:")
    # mood tracks sleep exactly, and ignores a constant feature
    for i in range(8):
        day = (DAY + timedelta(days=i)).isoformat()
        mood = 1 + i % 5
        conn.execute("INSERT INTO mood_log (day, mood, note, logged_at_utc) VALUES (?, ?, NULL, 'x')", (day, mood))
        conn.executemany("INSERT INTO day_feature VALUES (?, ?, ?)", [
            (day, "quiet_h", 4.0 + mood),
            (day, "pc_active_h", 10.0 - mood),
            (day, "events_today", 0.0),
        ])

    logged, ranked = features.correlations(conn, min_days=10)
    assert (logged, ranked) == (8, [])  # not enough days yet

    logged, ranked = features.correlations(conn, min_days=7)
    assert logged == 8
    assert ranked[0] == ("quiet_h", 1.0, 8)
    assert ("pc_active_h", -1.0, 8) in ranked
    assert all(name != "events_today" for name, _, _ in ranked)  # constant, so dropped
