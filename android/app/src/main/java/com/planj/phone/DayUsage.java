package com.planj.phone;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** One day of phone use, rebuilt from the saved events: totals, per-app time and sessions. */
final class DayUsage {
    static final class Session {
        final String pkg;
        final long startMs, endMs;

        Session(String pkg, long startMs, long endMs) {
            this.pkg = pkg;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    final LocalDate day;
    long screenMs;
    int unlocks;
    final Map<String, Long> appMs = new HashMap<>();
    final List<Session> sessions = new ArrayList<>();

    private DayUsage(LocalDate day) {
        this.day = day;
    }

    static DayUsage load(Context ctx, LocalDate day) {
        DayUsage u = new DayUsage(day);
        File file = new File(UsageCollector.eventsDir(ctx), day + ".jsonl");
        long onSince = -1, appSince = -1;
        String app = null;
        boolean today = day.equals(LocalDate.now());
        try (BufferedReader r = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                JSONObject o;
                try {
                    o = new JSONObject(line);
                } catch (JSONException e) {
                    continue; // a half-written last line
                }
                String event = o.optString("event");
                long t = Instant.parse(o.getString("t")).toEpochMilli();
                switch (event) {
                    case "screen_on":
                        if (onSince < 0) onSince = t;
                        break;
                    case "screen_off":
                    case "shutdown":
                        if (onSince >= 0) u.screenMs += t - onSince;
                        onSince = -1;
                        if (app != null) u.addSession(app, appSince, t);
                        app = null;
                        break;
                    case "unlock":
                        u.unlocks++;
                        break;
                    case "app_fg": {
                        String pkg = o.optString("app");
                        if (pkg.equals(app)) break;
                        if (app != null) u.addSession(app, appSince, t);
                        app = pkg;
                        appSince = t;
                        break;
                    }
                    case "app_bg":
                        if (o.optString("app").equals(app)) {
                            u.addSession(app, appSince, t);
                            app = null;
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException | JSONException | RuntimeException e) {
            return u; // no file yet
        }
        long now = System.currentTimeMillis();
        if (today) {
            if (onSince >= 0) u.screenMs += now - onSince;
            if (app != null) u.addSession(app, appSince, now);
        }
        return u;
    }

    private void addSession(String pkg, long start, long end) {
        if (end <= start || AppPalette.isSystemShell(pkg)) return;
        sessions.add(new Session(pkg, start, end));
        appMs.merge(pkg, end - start, Long::sum);
    }

    /** Apps by time, most used first. */
    List<Map.Entry<String, Long>> ranked() {
        List<Map.Entry<String, Long>> list = new ArrayList<>(appMs.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list;
    }

    long appTotalMs() {
        long total = 0;
        for (long v : appMs.values()) total += v;
        return total;
    }

    /**
     * Sleep as the longest quiet stretch across the night before {@code day}, ignoring brief
     * screen wakes from notifications. Returns 0 when there is no gap worth calling sleep.
     */
    static long estimateSleepMs(Context ctx, LocalDate day) {
        ZoneId zone = ZoneId.systemDefault();
        long windowStart = day.minusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli();
        long windowEnd = day.atTime(14, 0).atZone(zone).toInstant().toEpochMilli();
        long brief = TimeUnit.MINUTES.toMillis(3);

        List<long[]> awake = new ArrayList<>();
        for (LocalDate d : new LocalDate[]{day.minusDays(1), day}) {
            File file = new File(UsageCollector.eventsDir(ctx), d + ".jsonl");
            long onSince = -1;
            try (BufferedReader r = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    JSONObject o = new JSONObject(line);
                    String event = o.optString("event");
                    long t = Instant.parse(o.getString("t")).toEpochMilli();
                    if (event.equals("screen_on")) {
                        if (onSince < 0) onSince = t;
                    } else if ((event.equals("screen_off") || event.equals("shutdown")) && onSince >= 0) {
                        long s = Math.max(onSince, windowStart), e = Math.min(t, windowEnd);
                        if (e - s >= brief) awake.add(new long[]{s, e});
                        onSince = -1;
                    }
                }
            } catch (IOException | JSONException | RuntimeException e) {
                // missing day, keep going with what we have
            }
        }
        awake.sort((a, b) -> Long.compare(a[0], b[0]));
        long longest = 0;
        for (int i = 1; i < awake.size(); i++) {
            longest = Math.max(longest, awake.get(i)[0] - awake.get(i - 1)[1]);
        }
        return longest >= TimeUnit.HOURS.toMillis(2) ? longest : 0;
    }
}
