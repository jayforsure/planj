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

    /** The longest device-free stretch overnight, and whether the phone was charging through it. */
    static final class Quiet {
        final long startMs, endMs;
        final boolean charging;

        Quiet(long startMs, long endMs, boolean charging) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.charging = charging;
        }

        long ms() {
            return endMs - startMs;
        }
    }

    /**
     * Device-free time is not sleep: someone watching TV is just as silent. The longest
     * overnight gap is reported as what it is, and "on charge" makes sleep more likely.
     * Returns null when no gap is long enough to mean anything.
     */
    static Quiet quiet(Context ctx, LocalDate day) {
        ZoneId zone = ZoneId.systemDefault();
        long windowStart = day.minusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli();
        long windowEnd = day.atTime(14, 0).atZone(zone).toInstant().toEpochMilli();
        long brief = TimeUnit.MINUTES.toMillis(3);

        List<long[]> awake = new ArrayList<>();
        List<long[]> chargeChanges = new ArrayList<>(); // {time, 1 on / 0 off}
        for (LocalDate d : new LocalDate[]{day.minusDays(2), day.minusDays(1), day}) {
            File file = new File(UsageCollector.eventsDir(ctx), d + ".jsonl");
            long onSince = -1;
            try (BufferedReader r = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    JSONObject o = new JSONObject(line);
                    String event = o.optString("event");
                    long t = Instant.parse(o.getString("t")).toEpochMilli();
                    switch (event) {
                        case "screen_on":
                            if (onSince < 0) onSince = t;
                            break;
                        case "screen_off":
                        case "shutdown":
                            if (onSince >= 0) {
                                long s = Math.max(onSince, windowStart), e = Math.min(t, windowEnd);
                                if (e - s >= brief) awake.add(new long[]{s, e});
                                onSince = -1;
                            }
                            break;
                        case "charging_on":
                            chargeChanges.add(new long[]{t, 1});
                            break;
                        case "charging_off":
                            chargeChanges.add(new long[]{t, 0});
                            break;
                        default:
                            break;
                    }
                }
            } catch (IOException | JSONException | RuntimeException e) {
                // missing day, keep going with what we have
            }
        }
        awake.sort((a, b) -> Long.compare(a[0], b[0]));
        long bestStart = 0, bestEnd = 0;
        for (int i = 1; i < awake.size(); i++) {
            long s = awake.get(i - 1)[1], e = awake.get(i)[0];
            if (e - s > bestEnd - bestStart) {
                bestStart = s;
                bestEnd = e;
            }
        }
        if (bestEnd - bestStart < TimeUnit.HOURS.toMillis(2)) return null;

        // "On charge" means the phone was plugged in at any point during the gap. Android often
        // reports "unplugged" once the battery is full at 3am, which is not the person waking.
        boolean charging = false;
        for (long[] c : chargeChanges) {
            if (c[1] == 1 && c[0] >= bestStart - TimeUnit.HOURS.toMillis(2) && c[0] <= bestEnd) charging = true;
        }
        if (!charging) {
            boolean state = false;
            for (long[] c : chargeChanges) if (c[0] <= bestStart) state = c[1] == 1;
            charging = state;
        }
        return new Quiet(bestStart, bestEnd, charging);
    }
}
