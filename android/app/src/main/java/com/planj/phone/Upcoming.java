package com.planj.phone;

import android.content.Context;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What's coming up from your connectors: today's classes, exams and things due, soonest first. */
final class Upcoming {
    static final class Item {
        final int icon;
        final String title, subtitle, value;
        final long order;

        Item(int icon, String title, String subtitle, String value, long order) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.value = value;
            this.order = order;
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private Upcoming() {}

    /** "Today", "Tomorrow" or "6 days". */
    static String daysLeft(LocalDate d) {
        long n = ChronoUnit.DAYS.between(LocalDate.now(), d);
        return n <= 0 ? "Today" : n == 1 ? "Tomorrow" : n + " days";
    }

    /** Classes left today, then exams and things due within the days given. */
    static List<Item> soon(Context ctx, int days) {
        List<Item> out = new ArrayList<>();
        if (!TarcStore.connected(ctx)) return out;
        LocalDate today = LocalDate.now();
        int now = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        for (TarcTimetable.OnDay o : TarcTimetable.on(ctx, today)) {
            if (o.lesson.endMin <= now) continue;
            out.add(new Item(R.drawable.ic_today, o.course.name,
                    DayTimeline.clock(o.lesson.startMin) + "–" + DayTimeline.clock(o.lesson.endMin) + " · " + o.lesson.type + " · " + o.lesson.venue,
                    DayTimeline.clock(o.lesson.startMin), o.lesson.startMin));
        }
        for (TarcParse.Exam e : TarcExams.read(ctx)) {
            long in = ChronoUnit.DAYS.between(today, e.date);
            if (in < 0 || in > days || (in == 0 && e.startMin + e.minutes <= now)) continue;
            out.add(new Item(R.drawable.ic_edit, "Exam: " + e.name, e.date.format(DAY) + ", " + DayTimeline.clock(e.startMin) + " · " + e.venue,
                    daysLeft(e.date), in * 1440 + e.startMin));
        }
        for (TarcParse.Deadline d : TarcDue.soon(ctx, days)) {
            long in = ChronoUnit.DAYS.between(today, d.due);
            out.add(new Item(R.drawable.ic_bell, d.title, "Due " + d.due.format(DAY), daysLeft(d.due), in * 1440 + 1439));
        }
        out.sort((a, b) -> Long.compare(a.order, b.order));
        return out;
    }
}
