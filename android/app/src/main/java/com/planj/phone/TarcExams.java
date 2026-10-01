package com.planj.phone;

import android.content.Context;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Your exams, from the Exam Timetable page planj read from TAR UMT. */
final class TarcExams {
    private TarcExams() {}

    static List<TarcParse.Exam> read(Context ctx) {
        List<TarcParse.Exam> out = new ArrayList<>();
        for (JSONObject p : TarcStore.pages(ctx)) {
            if ("exams".equals(p.optString("kind")) || p.optString("url").contains("/exmtime/index.jsp")) {
                out.addAll(TarcParse.exams(p.optString("html")));
            }
        }
        out.sort((a, b) -> a.date.equals(b.date) ? Integer.compare(a.startMin, b.startMin) : a.date.compareTo(b.date));
        return out;
    }

    /** "Next: Data Structures and Algorithms · Wed 30 Sep, 9:00", or how many there were. */
    static String summary(List<TarcParse.Exam> exams) {
        LocalDateTime now = LocalDateTime.now();
        for (TarcParse.Exam e : exams) {
            if (e.date.atStartOfDay().plusMinutes(e.startMin + e.minutes).isAfter(now)) {
                return "Next: " + e.name + " · " + e.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
                        + ", " + DayTimeline.clock(e.startMin);
            }
        }
        LocalDate last = exams.get(exams.size() - 1).date;
        return exams.size() + (exams.size() == 1 ? " exam" : " exams") + " · the last was "
                + last.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
    }
}
