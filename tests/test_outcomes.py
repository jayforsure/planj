from datetime import date, timedelta

from planj import outcomes
from planj.db import connect

D0 = date(2026, 9, 1)


def _history(n: int):
    """n days where every odd evening is a short night, and the night after a short night
    is always long (7h+), the night after a long night always short."""
    hist = {}
    for i in range(n):
        short = i % 2 == 1
        hist[D0 + timedelta(days=i)] = {
            "phone_screen_h": 5.0 + (i % 3),
            "phone_social_h": 1.0,
            "phone_unlocks": 60.0 + i,
            "quiet_h": 5.0 if short else 8.0,
            "quiet_start_hour": 2.5 if short else 0.5,
            "weekday": (D0 + timedelta(days=i)).weekday(),
        }
    return hist


def test_forecast_picks_the_lever_that_splits_history():
    hist = _history(14)
    evening = D0 + timedelta(days=13)  # a short night, so tomorrow should be long
    fcs = {f.outcome: f for f in outcomes.forecast(hist, evening)}
    q = fcs["quiet_7h"]
    assert q.lever == "short_night" and q.lever_side is True
    assert q.prob > 0.8 and q.base_rate == 0.5
    off = fcs["off_by_1am"]
    assert off.lever == "short_night" and off.prob > 0.8


def test_no_forecast_without_enough_history():
    assert outcomes.forecast(_history(4), D0 + timedelta(days=3)) == []


def test_resolve_and_track_record():
    conn = connect(":memory:")
    hist = _history(12)
    evening = D0 + timedelta(days=10)
    fcs = outcomes.forecast(hist, evening)
    assert outcomes.store(conn, evening, fcs) == len(fcs)
    assert outcomes.store(conn, evening, fcs) == 0  # a forecast, once made, is not remade
    assert outcomes.settle(conn, hist) == len(fcs)
    rec = outcomes.track_record(conn)
    assert rec["quiet_7h"].n == 1 and rec["quiet_7h"].hits == 1


def test_backtest_beats_the_average_on_a_patterned_history():
    back = outcomes.backtest(_history(16))
    q = back["quiet_7h"]
    assert q.n >= 8
    assert q.hits >= q.base_hits
    # Brier is the honest score: a confident, right forecast beats a lukewarm average by a lot.
    assert q.brier / q.n < 0.1 < q.base_brier / q.n


def test_days_without_data_are_not_history():
    conn = connect(":memory:")
    conn.executemany("INSERT INTO day_feature VALUES (?, ?, ?)", [
        ("2026-09-01", "weekday", 1.0),              # empty day: only the calendar knows it exists
        ("2026-09-02", "phone_screen_h", 4.0),
    ])
    assert list(outcomes.load_history(conn)) == [date(2026, 9, 2)]
