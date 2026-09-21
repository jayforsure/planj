from datetime import date, datetime
from zoneinfo import ZoneInfo

from planj.db import connect
from planj.sources.calendar import parse
from planj.summary import summarize

KL = ZoneInfo("Asia/Kuala_Lumpur")

ICS = b"""BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//test//EN
BEGIN:VEVENT
UID:meeting-1
DTSTART:20260922T020000Z
DTEND:20260922T030000Z
SUMMARY:Client meeting
END:VEVENT
BEGIN:VEVENT
UID:holiday-1
DTSTART;VALUE=DATE:20260921
DTEND;VALUE=DATE:20260922
SUMMARY:Public holiday
END:VEVENT
BEGIN:VEVENT
UID:gym
DTSTART;TZID=Asia/Kuala_Lumpur:20260914T190000
DTEND;TZID=Asia/Kuala_Lumpur:20260914T200000
RRULE:FREQ=WEEKLY
SUMMARY:Gym
END:VEVENT
BEGIN:VEVENT
UID:cancelled-1
DTSTART:20260921T050000Z
DTEND:20260921T060000Z
STATUS:CANCELLED
SUMMARY:Cancelled thing
END:VEVENT
END:VCALENDAR
"""


def _rows():
    return parse(ICS, datetime(2026, 9, 20, tzinfo=KL), datetime(2026, 9, 23, tzinfo=KL), KL)


def test_parse_expands_recurrence_and_skips_cancelled():
    by_summary = {r[3]: r for r in _rows()}
    assert set(by_summary) == {"Client meeting", "Public holiday", "Gym"}
    assert by_summary["Gym"][1] == "2026-09-21T11:00:00.000000+00:00"
    assert by_summary["Public holiday"][4] == 1
    assert by_summary["Client meeting"][4] == 0


def test_summary_shows_today_and_tomorrow():
    conn = connect(":memory:")
    conn.executemany("INSERT INTO calendar_event VALUES (?, ?, ?, ?, ?)", _rows())
    s = summarize(conn, date(2026, 9, 21), KL)
    assert s.events_today == [("all day", "Public holiday"), ("19:00", "Gym")]
    assert s.events_tomorrow == [("10:00", "Client meeting")]
