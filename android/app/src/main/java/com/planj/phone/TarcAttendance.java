package com.planj.phone;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

/**
 * Every class TAR UMT has marked you at, kept on this phone across reads and semesters (a read's
 * pages only hold the current semester). Whether you made each day's first class is the answer
 * key for the class forecast.
 */
final class TarcAttendance {
    private TarcAttendance() {}

    /** The first class of a day that has a mark, and whether you were there. */
    static final class ClassDay {
        final LocalDate date;
        final int startMin;
        final boolean made;

        ClassDay(LocalDate date, int startMin, boolean made) {
            this.date = date;
            this.startMin = startMin;
            this.made = made;
        }
    }

    private static List<ClassDay> cached;
    private static long cachedFor = -1;

    static File file(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "tarc"), "attendance.json");
    }

    /** Adds the last read's attendance pages to the kept record; a class marked again keeps its newest mark. */
    static synchronized void record(Context ctx) {
        long read = TarcStore.state(ctx).optLong("read", 0);
        try {
            JSONObject kept = load(ctx);
            JSONObject marks = kept.optJSONObject("marks");
            if (marks == null) marks = new JSONObject();
            for (JSONObject p : TarcStore.pages(ctx)) {
                if (!"attendance".equals(p.optString("kind"))) continue;
                String course = Uri.parse(p.optString("url")).getQueryParameter("crs");
                if (course == null) course = "";
                for (TarcParse.Mark m : TarcParse.attendance(p.optString("html"))) {
                    marks.put(m.date + " " + m.startMin + " " + course, new JSONObject().put("d", m.date.toString())
                            .put("s", m.startMin).put("c", course).put("t", m.type)
                            .put("p", m.present == null ? JSONObject.NULL : m.present));
                }
            }
            kept.put("read", read).put("marks", marks);
            File f = file(ctx);
            File d = f.getParentFile();
            if (d != null && !d.isDirectory() && !d.mkdirs()) return;
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(kept.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | org.json.JSONException ignored) {
            // the next read tries again
        }
        cached = null;
    }

    /** Each day's first class with a mark, oldest first; empty when TAR UMT isn't connected. */
    static synchronized List<ClassDay> days(Context ctx) {
        long read = TarcStore.state(ctx).optLong("read", 0);
        if (read == 0) return new ArrayList<>();
        if (cached != null && cachedFor == read) return cached;
        if (load(ctx).optLong("read", -1) != read) record(ctx); // a read since the record was last kept
        TreeMap<LocalDate, int[]> first = new TreeMap<>(); // {start, 1 made / 0 missed / -1 neither}
        JSONObject marks = load(ctx).optJSONObject("marks");
        if (marks != null) {
            for (Iterator<String> it = marks.keys(); it.hasNext(); ) {
                JSONObject m = marks.optJSONObject(it.next());
                if (m == null) continue;
                try {
                    LocalDate date = LocalDate.parse(m.optString("d"));
                    int start = m.optInt("s", -1), made = m.isNull("p") ? -1 : m.optBoolean("p") ? 1 : 0;
                    int[] was = first.get(date);
                    if (start >= 0 && (was == null || start < was[0])) first.put(date, new int[]{start, made});
                } catch (RuntimeException ignored) {
                    // a damaged mark is skipped
                }
            }
        }
        List<ClassDay> out = new ArrayList<>();
        for (java.util.Map.Entry<LocalDate, int[]> e : first.entrySet()) {
            if (e.getValue()[1] >= 0) out.add(new ClassDay(e.getKey(), e.getValue()[0], e.getValue()[1] == 1));
        }
        cached = out;
        cachedFor = read;
        return out;
    }

    private static JSONObject load(Context ctx) {
        try {
            return new JSONObject(new String(Files.readAllBytes(file(ctx).toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
