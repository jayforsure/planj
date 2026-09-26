package com.planj.phone;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Copies the usage events Android keeps (for only a few days) into our own files, keeping just what we need. */
final class UsageCollector {
    private static final String PREFS = "planj";
    private static final String KEY_LAST = "last_event_ms";
    private static final String KEY_COUNT = "saved_count";
    static final String PRIVATE_APP = "private";

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
            // Private mode drops app identity entirely; private apps are recorded as "private"
            // so the time still counts, without saying which app it was.
            if (kind.startsWith("app_") && PrivateMode.covers(ctx, t)) continue;
            String pkg = e.getPackageName();
            if (kind.startsWith("app_") && PrivateMode.isPrivateApp(ctx, pkg)) pkg = PRIVATE_APP;
            JSONObject line = new JSONObject();
            try {
                line.put("t", Instant.ofEpochMilli(t).toString());
                line.put("event", kind);
                if (kind.startsWith("app_")) line.put("app", pkg);
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
        StringBuilder all = new StringBuilder();
        for (Map.Entry<String, StringBuilder> day : byDay.entrySet()) {
            try (OutputStream out = new FileOutputStream(new File(dir, day.getKey() + ".jsonl"), true)) {
                out.write(day.getValue().toString().getBytes(StandardCharsets.UTF_8));
            }
            all.append(day.getValue());
        }
        RelaySync.append(ctx, all.toString().getBytes(StandardCharsets.UTF_8));
        p.edit().putLong(KEY_LAST, last).putLong(KEY_COUNT, p.getLong(KEY_COUNT, 0) + saved).apply();
        saved += sampleState(ctx);
        return saved;
    }


    /**
     * Records charging, next-alarm and Do Not Disturb changes. These sharpen the sleep
     * estimate: a quiet night on charge with an alarm set is far more likely to be sleep
     * than a quiet evening in front of the TV.
     */
    private static int sampleState(Context ctx) throws IOException {
        SharedPreferences p = prefs(ctx);
        List<String> lines = new ArrayList<>();
        long now = System.currentTimeMillis();

        android.content.Intent battery = ctx.registerReceiver(null,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        boolean charging = battery != null && battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
        if (!p.contains("charging") || p.getBoolean("charging", false) != charging) {
            lines.add(stateLine(now, charging ? "charging_on" : "charging_off", null));
        }

        android.app.AlarmManager.AlarmClockInfo next = ctx.getSystemService(android.app.AlarmManager.class).getNextAlarmClock();
        String alarm = next == null ? "" : Instant.ofEpochMilli(next.getTriggerTime()).toString();
        if (!alarm.equals(p.getString("alarm", null))) {
            lines.add(stateLine(now, "next_alarm", alarm));
        }

        int filter = ctx.getSystemService(android.app.NotificationManager.class).getCurrentInterruptionFilter();
        boolean dnd = filter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                && filter != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        if (!p.contains("dnd") || p.getBoolean("dnd", false) != dnd) {
            lines.add(stateLine(now, dnd ? "dnd_on" : "dnd_off", null));
        }

        if (lines.isEmpty()) return 0;
        File dir = eventsDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        String day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString();
        StringBuilder all = new StringBuilder();
        for (String l : lines) all.append(l).append('\n');
        byte[] bytes = all.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = new FileOutputStream(new File(dir, day + ".jsonl"), true)) {
            out.write(bytes);
        }
        RelaySync.append(ctx, bytes);
        p.edit().putBoolean("charging", charging).putString("alarm", alarm).putBoolean("dnd", dnd).apply();
        return lines.size();
    }

    private static String stateLine(long t, String event, String value) throws IOException {
        try {
            JSONObject o = new JSONObject().put("t", Instant.ofEpochMilli(t).toString()).put("event", event);
            if (value != null && !value.isEmpty()) o.put("app", value); // reuses the app slot for the alarm time
            return o.toString();
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    static boolean lastKnownCharging(Context ctx) {
        return prefs(ctx).getBoolean("charging", false);
    }

    /** Today's screen-on milliseconds and unlock count, rebuilt from the saved events. */
    static long[] todayStats(Context ctx) {
        return dayStats(ctx, LocalDate.now());
    }

    /** Screen-on milliseconds and unlocks for one day; a screen still on counts up to now. */
    static long[] dayStats(Context ctx, LocalDate day) {
        File file = new File(eventsDir(ctx), day.toString() + ".jsonl");
        long screenMs = 0, unlocks = 0, onSince = -1;
        try (BufferedReader r = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                JSONObject o = new JSONObject(line);
                String event = o.optString("event");
                long t = Instant.parse(o.getString("t")).toEpochMilli();
                if (event.equals("screen_on")) {
                    if (onSince < 0) onSince = t;
                } else if (event.equals("screen_off") || event.equals("shutdown")) {
                    if (onSince >= 0) screenMs += t - onSince;
                    onSince = -1;
                } else if (event.equals("unlock")) {
                    unlocks++;
                }
            }
        } catch (IOException | JSONException | RuntimeException e) {
            return new long[]{0, 0}; // no file yet, or a half-written last line
        }
        if (onSince >= 0 && day.equals(LocalDate.now())) screenMs += System.currentTimeMillis() - onSince;
        return new long[]{screenMs, unlocks};
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
