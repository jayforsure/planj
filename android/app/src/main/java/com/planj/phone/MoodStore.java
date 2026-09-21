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

    private MoodStore() {}

    static LocalDate moodDay(ZonedDateTime now) {
        return now.getHour() < DAY_ROLLOVER_HOUR ? now.toLocalDate().minusDays(1) : now.toLocalDate();
    }

    static LocalDate today() {
        return moodDay(ZonedDateTime.now());
    }

    static synchronized void save(Context ctx, LocalDate day, int mood, String note) throws IOException {
        JSONObject line = new JSONObject();
        try {
            line.put("t", Instant.now().toString());
            line.put("event", "mood");
            line.put("day", day.toString());
            line.put("mood", mood);
            if (note != null && !note.trim().isEmpty()) line.put("note", note.trim());
        } catch (JSONException e) {
            throw new IOException(e);
        }
        File dir = UsageCollector.eventsDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        try (OutputStream out = new FileOutputStream(new File(dir, "mood.jsonl"), true)) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        prefs(ctx).edit().putString("day", day.toString()).putInt("mood", mood).apply();
    }

    /** The mood logged for {@code day}, or 0 if none. */
    static int moodFor(Context ctx, LocalDate day) {
        SharedPreferences p = prefs(ctx);
        return day.toString().equals(p.getString("day", null)) ? p.getInt("mood", 0) : 0;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
