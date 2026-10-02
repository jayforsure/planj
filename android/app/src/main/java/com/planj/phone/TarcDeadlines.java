package com.planj.phone;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether you finish TAR UMT's to-dos in time, checked by the dashboard itself: a task that
 * leaves the dashboard before its due date was done in time; one still there on the last day
 * was not. Only things you act on count (evaluations, surveys, submissions), not "view by" windows.
 */
final class TarcDeadlines {
    static final int MIN_SEEN = 5; // settled tasks before planj shows odds instead of "learning"
    private static final Pattern TASK = Pattern.compile("evaluation|survey|submission|submit|registration|feedback", Pattern.CASE_INSENSITIVE);

    /** A task on the dashboard now. */
    static final class Pending {
        final String title;
        final LocalDate due;

        Pending(String title, LocalDate due) {
            this.title = title;
            this.due = due;
        }
    }

    private TarcDeadlines() {}

    static boolean task(String title) {
        return TASK.matcher(title).find();
    }

    private static File file(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "tarc"), "deadlines.json");
    }

    private static JSONObject load(Context ctx) {
        try {
            return new JSONObject(new String(Files.readAllBytes(file(ctx).toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** After a read: note the tasks on the dashboard, and settle the ones that have left it. */
    static synchronized void record(Context ctx) {
        boolean dashboard = false;
        for (JSONObject p : TarcStore.pages(ctx)) if ("home".equals(p.optString("kind"))) dashboard = true;
        if (!dashboard) return; // without the dashboard, absence means nothing
        JSONObject log = load(ctx);
        long now = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        Set<String> present = new HashSet<>();
        try {
            for (TarcParse.Deadline d : TarcDue.read(ctx)) {
                if (!task(d.title) || d.due.isAfter(today.plusYears(1))) continue;
                String key = d.title + "|" + d.due;
                present.add(key);
                JSONObject e = log.optJSONObject(key);
                if (e == null) {
                    e = new JSONObject().put("title", d.title).put("due", d.due.toString()).put("first", now);
                    log.put(key, e);
                }
                if (!e.has("done")) e.put("last", now);
            }
            for (Iterator<String> it = log.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject e = log.getJSONObject(key);
                if (e.has("done") || present.contains(key)) continue;
                LocalDate due = LocalDate.parse(e.getString("due"));
                long dueStart = due.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                if (!today.isAfter(due)) e.put("done", true).put("settled", now);          // gone before it was due
                else if (e.optLong("last") >= dueStart - 86_400_000L) e.put("done", false).put("settled", now); // there to the end
                else e.put("done", JSONObject.NULL).put("settled", now);                   // not seen near the end: unknown
            }
            File f = file(ctx);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(log.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // the next read records it
        }
    }

    /** {done in time, settled}: tasks whose outcome is known. */
    static int[] counts(Context ctx) {
        int done = 0, settled = 0;
        JSONObject log = load(ctx);
        for (Iterator<String> it = log.keys(); it.hasNext(); ) {
            JSONObject e = log.optJSONObject(it.next());
            if (e == null || !e.has("done") || e.isNull("done")) continue;
            settled++;
            if (e.optBoolean("done")) done++;
        }
        return new int[]{done, settled};
    }

    /** "64%" once enough tasks have settled; null while planj is still learning. */
    static String odds(Context ctx) {
        int[] r = counts(ctx);
        if (r[1] < MIN_SEEN) return null;
        return Math.round(100.0 * (r[0] + 1) / (r[1] + 2)) + "%";
    }

    static int settled(Context ctx) {
        return counts(ctx)[1];
    }

    /** Tasks on the dashboard still to do, soonest first. */
    static List<Pending> pending(Context ctx) {
        List<Pending> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (TarcParse.Deadline d : TarcDue.read(ctx)) {
            if (task(d.title) && !d.due.isBefore(today) && !d.due.isAfter(today.plusYears(1))) out.add(new Pending(d.title, d.due));
        }
        return out;
    }
}
