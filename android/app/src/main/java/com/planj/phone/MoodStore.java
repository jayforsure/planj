package com.planj.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/** Daily mood ratings, appended to the same folder the export reads. */
final class MoodStore {
    private static final String PREFS = "planj_mood";
    // Logging "how was today" after midnight almost always means the day that just ended.
    private static final int DAY_ROLLOVER_HOUR = 4;

    /** What is saved for a day: mood 1–5, the note, and when it was last saved. */
    static final class Entry {
        final int mood;
        final String note;
        final long savedAtMs;

        Entry(int mood, String note, long savedAtMs) {
            this.mood = mood;
            this.note = note;
            this.savedAtMs = savedAtMs;
        }
    }

    private MoodStore() {}

    static LocalDate moodDay(ZonedDateTime now) {
        return now.getHour() < DAY_ROLLOVER_HOUR ? now.toLocalDate().minusDays(1) : now.toLocalDate();
    }

    static LocalDate today() {
        return moodDay(ZonedDateTime.now());
    }

    static synchronized void save(Context ctx, LocalDate day, int mood, String note) throws IOException {
        String trimmed = note == null ? "" : note.trim();
        JSONObject line = new JSONObject();
        try {
            line.put("t", Instant.now().toString());
            line.put("event", "mood");
            line.put("day", day.toString());
            line.put("mood", mood);
            if (!trimmed.isEmpty()) line.put("note", trimmed);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        File dir = UsageCollector.eventsDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = new FileOutputStream(new File(dir, "mood.jsonl"), true)) {
            out.write(bytes);
        }
        RelaySync.append(ctx, bytes);
        prefs(ctx).edit()
                .putString("day", day.toString())
                .putInt("mood", mood)
                .putString("note", trimmed)
                .putLong("saved_ms", System.currentTimeMillis())
                .apply();
    }

    /** The entry saved for {@code day}, or null if none. */
    static Entry entryFor(Context ctx, LocalDate day) {
        SharedPreferences p = prefs(ctx);
        if (!day.toString().equals(p.getString("day", null))) return null;
        if (!p.contains("note")) {
            // Saved by an older version that kept only the mood; the file has the rest.
            Entry fromFile = lastInFile(ctx, day);
            if (fromFile != null) return fromFile;
        }
        return new Entry(p.getInt("mood", 0), p.getString("note", ""), p.getLong("saved_ms", 0));
    }

    private static Entry lastInFile(Context ctx, LocalDate day) {
        Entry found = null;
        File file = new File(UsageCollector.eventsDir(ctx), "mood.jsonl");
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(file, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                JSONObject o = new JSONObject(line);
                if (day.toString().equals(o.optString("day"))) {
                    found = new Entry(o.getInt("mood"), o.optString("note", ""),
                            Instant.parse(o.getString("t")).toEpochMilli());
                }
            }
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
        return found;
    }

    static int moodFor(Context ctx, LocalDate day) {
        Entry e = entryFor(ctx, day);
        return e == null ? 0 : e.mood;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
