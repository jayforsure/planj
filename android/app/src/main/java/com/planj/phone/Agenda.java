package com.planj.phone;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Tomorrow's plans, read from the calendar the person already keeps — no typing them again. */
final class Agenda {
    static final class Event {
        final String title;
        final long startMs;
        final boolean allDay;

        Event(String title, long startMs, boolean allDay) {
            this.title = title;
            this.startMs = startMs;
            this.allDay = allDay;
        }
    }

    private Agenda() {}

    static boolean allowed(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    static List<Event> on(Context ctx, LocalDate day) {
        List<Event> out = new ArrayList<>();
        if (!allowed(ctx)) return out;
        ZoneId zone = ZoneId.systemDefault();
        long from = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        String[] cols = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY};
        try (Cursor c = ctx.getContentResolver().query(b.build(), cols, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
            while (c != null && c.moveToNext()) {
                String title = c.getString(0);
                if (title == null || title.isBlank()) continue;
                out.add(new Event(title.trim(), c.getLong(1), c.getInt(2) == 1));
            }
        } catch (SecurityException e) {
            // permission revoked mid-flight; treat as no calendar
        }
        return out;
    }

    /** The first timed event of the day, or null. */
    static Event firstTimed(List<Event> events) {
        for (Event e : events) if (!e.allDay) return e;
        return null;
    }
}
