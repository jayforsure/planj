package com.planj.phone;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Daily mood entries, kept as an append-only file; the last line for a day wins. */
final class MoodStore {
    // Logging "how was today" after midnight almost always means the day that just ended.
    private static final int DAY_ROLLOVER_HOUR = 4;
    static final String[] TAGS = {
            "study", "work", "exercise", "social", "family", "sick",
            "tired", "trading", "anime", "gaming", "travel", "alone",
    };

    static final class Entry {
        final int mood;
        final String note;
        final List<String> tags;
        final long savedAtMs;

        Entry(int mood, String note, List<String> tags, long savedAtMs) {
            this.mood = mood;
            this.note = note;
            this.tags = tags;
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

    static synchronized void save(Context ctx, LocalDate day, int mood, String note, List<String> tags) throws IOException {
        String trimmed = note == null ? "" : note.trim();
        JSONObject line = new JSONObject();
        try {
            line.put("t", Instant.now().toString());
            line.put("event", "mood");
            line.put("day", day.toString());
            line.put("mood", mood);
            if (!trimmed.isEmpty()) line.put("note", trimmed);
            if (tags != null && !tags.isEmpty()) line.put("tags", new JSONArray(tags));
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
    }

    /** Every day that has an entry, oldest first. */
    static synchronized Map<LocalDate, Entry> all(Context ctx) {
        Map<LocalDate, Entry> out = new TreeMap<>();
        File file = new File(UsageCollector.eventsDir(ctx), "mood.jsonl");
        try (BufferedReader r = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                try {
                    JSONObject o = new JSONObject(line);
                    List<String> tags = new ArrayList<>();
                    JSONArray arr = o.optJSONArray("tags");
                    if (arr != null) for (int i = 0; i < arr.length(); i++) tags.add(arr.getString(i));
                    out.put(LocalDate.parse(o.getString("day")), new Entry(
                            o.getInt("mood"), o.optString("note", ""), tags,
                            Instant.parse(o.getString("t")).toEpochMilli()));
                } catch (JSONException | RuntimeException e) {
                    // skip a bad line rather than lose the rest
                }
            }
        } catch (IOException e) {
            // no entries yet
        }
        return out;
    }

    static Entry entryFor(Context ctx, LocalDate day) {
        return all(ctx).get(day);
    }

    static int moodFor(Context ctx, LocalDate day) {
        Entry e = entryFor(ctx, day);
        return e == null ? 0 : e.mood;
    }
}
