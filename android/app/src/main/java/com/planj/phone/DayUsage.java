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
    long lateNightMs; // screen on between midnight and 05:00 of this day
    int unlocks;
    final Map<String, Long> appMs = new HashMap<>();
    final List<Session> sessions = new ArrayList<>();
    final List<long[]> screenOn = new ArrayList<>();   // [startMs, endMs]
    final List<String[]> net = new ArrayList<>();       // [tMs, "wifi:<fingerprint>" | "mobile" | "none"]
    final List<String[]> place = new ArrayList<>();     // [tMs, "p3"]
    final List<long[]> charge = new ArrayList<>();      // [tMs, 1 plugged in / 0 unplugged]

    private DayUsage(LocalDate day) {
        this.day = day;
    }

    private static final java.util.Map<String, DayUsage> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Parsed days are cached by file identity, so a week's chart only re-reads what changed. */
    static DayUsage load(Context ctx, LocalDate day) {
        File file = new File(UsageCollector.eventsDir(ctx), day + ".jsonl");
        boolean today = day.equals(LocalDate.now());
        String key = day + "|" + file.length() + "|" + file.lastModified();
        if (today) return parse(ctx, day, file);
        DayUsage hit = CACHE.get(key);
        if (hit != null) return hit;
        if (CACHE.size() > 120) CACHE.clear();
        return CACHE.computeIfAbsent(key, k -> parse(ctx, day, file)); // two threads asking at once parse it once
    }

    private static DayUsage parse(Context ctx, LocalDate day, File file) {
        DayUsage u = new DayUsage(day);
        long onSince = -1, appSince = -1;
        String app = null;
        boolean today = day.equals(LocalDate.now());
        ZoneId zone = ZoneId.systemDefault();
        long nightStart = day.atStartOfDay(zone).toInstant().toEpochMilli();
        long nightEnd = day.atTime(5, 0).atZone(zone).toInstant().toEpochMilli();
        try (BufferedReader r = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                String event, tRaw, appVal;
                if (line.indexOf('\\') < 0) {
                    // Every event is {"t":…,"event":…,"app":…} with plain values; reading the three
                    // fields directly is many times quicker than a general JSON parse over a month.
                    event = field(line, "event");
                    tRaw = field(line, "t");
                    appVal = field(line, "app");
                    if (event == null || tRaw == null) continue; // a half-written last line
                } else {
                    try {
                        JSONObject o = new JSONObject(line);
                        event = o.optString("event");
                        tRaw = o.getString("t");
                        appVal = o.optString("app");
                    } catch (JSONException e) {
                        continue;
                    }
                }
                if (appVal == null) appVal = "";
                long t;
                try {
                    t = millis(tRaw);
                } catch (RuntimeException e) {
                    continue;
                }
                switch (event) {
                    case "screen_on":
                        if (onSince < 0) onSince = t;
                        break;
                    case "screen_off":
                    case "shutdown":
                        if (onSince >= 0) {
                            u.screenOn.add(new long[]{onSince, t});
                            u.screenMs += t - onSince;
                            u.lateNightMs += Math.max(0, Math.min(t, nightEnd) - Math.max(onSince, nightStart));
                        }
                        onSince = -1;
                        if (app != null) u.addSession(app, appSince, t);
                        app = null;
                        break;
                    case "unlock":
                        u.unlocks++;
                        break;
                    case "charging_on":
                        u.charge.add(new long[]{t, 1});
                        break;
                    case "charging_off":
                        u.charge.add(new long[]{t, 0});
                        break;
                    case "net":
                        u.net.add(new String[]{Long.toString(t), appVal});
                        break;
                    case "place":
                        u.place.add(new String[]{Long.toString(t), appVal});
                        break;
                    case "app_fg": {
                        String pkg = appVal;
                        if (pkg.equals(app)) break;
                        if (app != null) u.addSession(app, appSince, t);
                        app = pkg;
                        appSince = t;
                        break;
                    }
                    case "app_bg":
                        if (appVal.equals(app)) {
                            u.addSession(app, appSince, t);
                            app = null;
                        }
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException | RuntimeException e) {
            return u; // no file yet
        }
        long now = System.currentTimeMillis();
        if (today) {
            if (onSince >= 0) {
                u.screenMs += now - onSince;
                u.screenOn.add(new long[]{onSince, now});
            }
            if (app != null) u.addSession(app, appSince, now);
        }
        return u;
    }

    /** The string value of "key" in a flat JSON line without escapes, or null when absent. */
    static String field(String line, String key) {
        String k = "\"" + key + "\":\"";
        int i = line.indexOf(k);
        if (i < 0) return null;
        int from = i + k.length(), to = line.indexOf('"', from);
        return to < 0 ? null : line.substring(from, to);
    }

    /**
     * "2026-09-28T16:13:38.074Z" to epoch milliseconds. Events are always written in this
     * shape, and reading it by position is far quicker than the general parser over a
     * month of lines; anything else falls back to that parser.
     */
    static long millis(String t) {
        int n = t.length();
        if ((n == 24 || n == 20) && t.charAt(4) == '-' && t.charAt(10) == 'T' && t.charAt(n - 1) == 'Z'
                && (n == 20 || t.charAt(19) == '.')) {
            try {
                long days = LocalDate.of(num(t, 0, 4), num(t, 5, 7), num(t, 8, 10)).toEpochDay();
                long secs = days * 86400L + num(t, 11, 13) * 3600L + num(t, 14, 16) * 60L + num(t, 17, 19);
                return secs * 1000 + (n == 24 ? num(t, 20, 23) : 0);
            } catch (RuntimeException e) {
                // not the usual shape after all
            }
        }
        return Instant.parse(t).toEpochMilli();
    }

    private static int num(String s, int from, int to) {
        int v = 0;
        for (int i = from; i < to; i++) {
            int d = s.charAt(i) - '0';
            if (d < 0 || d > 9) throw new NumberFormatException(s);
            v = v * 10 + d;
        }
        return v;
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
        // The three days come from the parsed-day cache, so a run over many nights reads each file once.
        for (LocalDate d : new LocalDate[]{day.minusDays(2), day.minusDays(1), day}) {
            DayUsage u = load(ctx, d);
            for (long[] on : u.screenOn) {
                long s = Math.max(on[0], windowStart), e = Math.min(on[1], windowEnd);
                if (e - s >= brief) awake.add(new long[]{s, e});
            }
            chargeChanges.addAll(u.charge);
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
