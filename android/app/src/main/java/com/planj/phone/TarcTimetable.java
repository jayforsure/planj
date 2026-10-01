package com.planj.phone;

import android.content.Context;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Your weekly classes from TAR UMT, and which of them fall on a given day of the semester. */
final class TarcTimetable {
    private TarcTimetable() {}

    /** A class on a particular day. */
    static final class OnDay {
        final TarcParse.Course course;
        final TarcParse.Lesson lesson;

        OnDay(TarcParse.Course course, TarcParse.Lesson lesson) {
            this.course = course;
            this.lesson = lesson;
        }
    }

    private static List<TarcParse.Course> cached;
    private static long cachedFor = -1;

    /** The courses on the newest timetable read; empty when not connected. */
    static synchronized List<TarcParse.Course> courses(Context ctx) {
        long read = TarcStore.state(ctx).optLong("read", 0);
        if (cached != null && cachedFor == read) return cached;
        List<TarcParse.Course> out = new ArrayList<>();
        for (JSONObject p : TarcStore.pages(ctx)) {
            if ("timetable".equals(p.optString("kind"))) out.addAll(TarcParse.courses(p.optString("html")));
        }
        cached = out;
        cachedFor = read;
        return out;
    }

    static int lessonsPerWeek(List<TarcParse.Course> courses) {
        int n = 0;
        for (TarcParse.Course c : courses) n += c.lessons.size();
        return n;
    }

    /**
     * Whether a day is a teaching day of the semester read: null when nothing is known (not
     * connected), false outside the semester's dates.
     */
    static Boolean inSemester(Context ctx, LocalDate day) {
        JSONObject st = TarcStore.state(ctx);
        if (!st.has("start") || !st.has("end") || courses(ctx).isEmpty()) return null;
        try {
            return !day.isBefore(LocalDate.parse(st.getString("start"))) && !day.isAfter(LocalDate.parse(st.getString("end")));
        } catch (Exception e) {
            return null;
        }
    }

    /** The classes on a day, earliest first; empty outside the semester or when not connected. */
    static List<OnDay> on(Context ctx, LocalDate day) {
        List<OnDay> out = new ArrayList<>();
        if (!Boolean.TRUE.equals(inSemester(ctx, day))) return out;
        for (TarcParse.Course c : courses(ctx)) {
            for (TarcParse.Lesson l : c.lessons) if (l.day == day.getDayOfWeek()) out.add(new OnDay(c, l));
        }
        out.sort((a, b) -> Integer.compare(a.lesson.startMin, b.lesson.startMin));
        return out;
    }

    /** The first class's start on a day in minutes after midnight, -1 for none, null when unknown. */
    static Integer firstClass(Context ctx, LocalDate day) {
        if (inSemester(ctx, day) == null) return null;
        List<OnDay> on = on(ctx, day);
        return on.isEmpty() ? -1 : on.get(0).lesson.startMin;
    }

    /** Tomorrow's classes as plans next to the calendar's, for Odds. */
    static List<Agenda.Event> asPlans(Context ctx, LocalDate day) {
        List<Agenda.Event> out = new ArrayList<>();
        long midnight = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        for (OnDay o : on(ctx, day)) {
            out.add(new Agenda.Event(o.course.name + " · " + o.lesson.type + " · " + o.lesson.venue,
                    midnight + o.lesson.startMin * 60_000L, false));
        }
        return out;
    }
}
