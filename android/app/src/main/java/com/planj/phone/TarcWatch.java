package com.planj.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** What changed on TAR UMT between two reads, and telling you when it matters. */
final class TarcWatch {
    private static final String CHANNEL = "tarc";
    private static final int NOTIFICATION = 7301;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private TarcWatch() {}

    /** The things worth noticing, as they stand now. */
    static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            JSONObject st = TarcStore.state(ctx);
            o.put("read", st.optLong("read", 0)).put("session", st.optString("session"));
            StringBuilder tt = new StringBuilder();
            List<TarcParse.Course> courses = TarcTimetable.courses(ctx);
            for (TarcParse.Course c : courses) {
                tt.append(c.code);
                for (TarcParse.Lesson l : c.lessons) tt.append(l.day).append(l.startMin).append(l.endMin).append(l.venue);
                tt.append(';');
            }
            o.put("timetable", tt.toString()).put("classes", TarcTimetable.lessonsPerWeek(courses));
            JSONArray exams = new JSONArray();
            for (TarcParse.Exam e : TarcExams.read(ctx)) exams.put(e.code + "|" + e.date + "|" + e.startMin);
            o.put("exams", exams);
            boolean hidden = false, results = false;
            for (JSONObject p : TarcStore.pages(ctx)) {
                if (!"results".equals(p.optString("kind"))) continue;
                results = true;
                if (TarcParse.resultsHidden(p.optString("html"))) hidden = true;
            }
            o.put("results", results && !hidden);
            JSONArray due = new JSONArray();
            for (TarcParse.Deadline d : TarcDue.read(ctx)) due.put(d.title + "|" + d.due);
            o.put("due", due);
        } catch (Exception ignored) {
            // an incomplete snapshot just notices less
        }
        return o;
    }

    /** One line per change worth a notification; nothing on the very first read. */
    static List<String> changes(Context ctx, JSONObject before, JSONObject after) {
        List<String> out = new ArrayList<>();
        if (before.optLong("read", 0) == 0) return out; // the first read is the starting point, not news
        if (!before.optBoolean("results") && after.optBoolean("results")) out.add("Your results are out");
        String s0 = before.optString("session"), s1 = after.optString("session");
        if (!s1.isEmpty() && !s1.equals(s0)) {
            out.add("New semester timetable: " + after.optInt("classes") + " classes a week");
        } else if (!after.optString("timetable").isEmpty() && !after.optString("timetable").equals(before.optString("timetable"))) {
            out.add("Your timetable changed");
        }
        Set<String> oldExams = set(before.optJSONArray("exams"));
        int added = 0;
        for (String e : list(after.optJSONArray("exams"))) if (!oldExams.contains(e)) added++;
        if (added > 0) {
            List<TarcParse.Exam> exams = TarcExams.read(ctx);
            out.add((added == 1 ? "New exam" : added + " new exams") + (exams.isEmpty() ? "" : ". " + TarcExams.summary(exams)));
        }
        Set<String> oldDue = set(before.optJSONArray("due"));
        for (String d : list(after.optJSONArray("due"))) {
            if (oldDue.contains(d)) continue;
            String[] parts = d.split("\\|");
            try {
                LocalDate due = LocalDate.parse(parts[1]);
                if (due.isBefore(LocalDate.now()) || due.isAfter(LocalDate.now().plusDays(120))) continue;
                out.add("Due " + due.format(DAY) + ": " + parts[0]);
            } catch (RuntimeException ignored) {
                // not a date
            }
        }
        return out;
    }

    static void notify(Context ctx, List<String> lines) {
        if (lines.isEmpty()) return;
        post(ctx, lines.size() == 1 ? "TAR UMT" : "TAR UMT · " + lines.size() + " updates", String.join("\n", lines));
    }

    /** The saved password stopped working (changed on TAR UMT): say so once. */
    static void notifyStopped(Context ctx) {
        post(ctx, "TAR UMT", "Automatic refresh stopped: TAR UMT didn't accept your saved password. Open planj to sign in again.");
    }

    private static void post(Context ctx, String title, String text) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "TAR UMT updates", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(ctx, NOTIFICATION, new Intent(ctx, TarcActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String first = text.split("\n")[0];
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_school)
                .setContentTitle(title)
                .setContentText(first)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(NOTIFICATION, n);
        } catch (SecurityException ignored) {
            // notifications are off for planj; the TAR UMT page still shows everything
        }
    }

    private static Set<String> set(JSONArray a) {
        return new HashSet<>(list(a));
    }

    private static List<String> list(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }
}
