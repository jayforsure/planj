package com.planj.phone;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Copies the usage events Android keeps (for only a few days) into our own files, keeping just what we need. */
final class UsageCollector {
    private static final String PREFS = "planj";
    private static final String KEY_LAST = "last_event_ms";
    private static final String KEY_COUNT = "saved_count";

    // UsageEvents.Event type values; named here because several constants are newer than minSdk.
    private static final int ACTIVITY_RESUMED = 1;
    private static final int ACTIVITY_PAUSED = 2;
    private static final int SCREEN_INTERACTIVE = 15;
    private static final int SCREEN_NON_INTERACTIVE = 16;
    private static final int KEYGUARD_HIDDEN = 18;
    private static final int DEVICE_SHUTDOWN = 26;
    private static final int DEVICE_STARTUP = 27;

    private UsageCollector() {}

    private static String kind(int type) {
        switch (type) {
            case ACTIVITY_RESUMED: return "app_fg";
            case ACTIVITY_PAUSED: return "app_bg";
            case SCREEN_INTERACTIVE: return "screen_on";
            case SCREEN_NON_INTERACTIVE: return "screen_off";
            case KEYGUARD_HIDDEN: return "unlock";
            case DEVICE_SHUTDOWN: return "shutdown";
            case DEVICE_STARTUP: return "startup";
            default: return null; // notifications, config changes etc. are never stored
        }
    }

    static File eventsDir(Context ctx) {
        return new File(ctx.getFilesDir(), "events");
    }

    static long savedCount(Context ctx) {
        return prefs(ctx).getLong(KEY_COUNT, 0);
    }

    static long lastEventMs(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST, 0);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Saves events newer than the last run. Returns how many were saved. */
    static synchronized int collect(Context ctx) throws IOException {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        // First run: take whatever history Android still holds.
        long since = p.getLong(KEY_LAST, now - TimeUnit.DAYS.toMillis(30));

        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        UsageEvents events = usm.queryEvents(since + 1, now);
        UsageEvents.Event e = new UsageEvents.Event();
        ZoneId zone = ZoneId.systemDefault();
        Map<String, StringBuilder> byDay = new TreeMap<>();
        long last = since;
        int saved = 0;

        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            String kind = kind(e.getEventType());
            if (kind == null) continue;
            long t = e.getTimeStamp();
            JSONObject line = new JSONObject();
            try {
                line.put("t", Instant.ofEpochMilli(t).toString());
                line.put("event", kind);
                if (kind.startsWith("app_")) line.put("app", e.getPackageName());
            } catch (JSONException ex) {
                throw new IOException(ex);
            }
            String day = Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toString();
            byDay.computeIfAbsent(day, d -> new StringBuilder()).append(line).append('\n');
            last = Math.max(last, t);
            saved++;
        }

        File dir = eventsDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        for (Map.Entry<String, StringBuilder> day : byDay.entrySet()) {
            try (OutputStream out = new FileOutputStream(new File(dir, day.getKey() + ".jsonl"), true)) {
                out.write(day.getValue().toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        p.edit().putLong(KEY_LAST, last).putLong(KEY_COUNT, p.getLong(KEY_COUNT, 0) + saved).apply();
        return saved;
    }

    /** Writes every saved day, oldest first, as one JSONL stream. */
    static void export(Context ctx, OutputStream out) throws IOException {
        File[] files = eventsDir(ctx).listFiles((d, name) -> name.endsWith(".jsonl"));
        if (files == null) return;
        Arrays.sort(files);
        byte[] buf = new byte[8192];
        for (File f : files) {
            try (InputStream in = new FileInputStream(f)) {
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
        }
    }
}
