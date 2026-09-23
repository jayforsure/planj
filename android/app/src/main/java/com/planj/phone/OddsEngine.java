package com.planj.phone;

import android.content.Context;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Behavioural odds from the user's own history: how often did this happen on comparable
 * days? Every number carries the count it rests on, so a 75% from four days says so.
 */
final class OddsEngine {
    static final class Odd {
        final String section, question, evidence;
        final int hits, days;
        final String pkg; // for an app colour, or null

        Odd(String section, String question, int hits, int days, String evidence, String pkg) {
            this.section = section;
            this.question = question;
            this.hits = hits;
            this.days = days;
            this.evidence = evidence;
            this.pkg = pkg;
        }

        int percent() {
            return days == 0 ? 0 : Math.round(100f * hits / days);
        }
    }

    private static final int HISTORY_DAYS = 21;
    private static final int MIN_DAYS = 3;

    private OddsEngine() {}

    static List<Odd> compute(Context ctx) {
        LocalDate today = LocalDate.now();
        List<DayUsage> history = new ArrayList<>();
        Map<LocalDate, DayUsage.Quiet> quiets = new HashMap<>();
        for (int i = 1; i <= HISTORY_DAYS; i++) {
            LocalDate d = today.minusDays(i);
            DayUsage u = DayUsage.load(ctx, d);
            if (u.screenMs == 0 && u.appMs.isEmpty()) continue; // no data that day
            history.add(u);
            quiets.put(d, DayUsage.quiet(ctx, d));
        }
        List<Odd> out = new ArrayList<>();
        if (history.size() < MIN_DAYS) return out;
        int n = history.size();
        ZoneId zone = ZoneId.systemDefault();

        // Next hour: on past days, was the app used in this same hour?
        LocalTime now = LocalTime.now();
        int hourStart = now.getHour();
        Map<String, Long> weekTotals = new HashMap<>();
        for (DayUsage u : history) for (Map.Entry<String, Long> e : u.appMs.entrySet()) weekTotals.merge(e.getKey(), e.getValue(), Long::sum);
        List<Map.Entry<String, Long>> top = new ArrayList<>(weekTotals.entrySet());
        top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        int shown = 0;
        for (Map.Entry<String, Long> e : top) {
            if (shown++ == 4) break;
            String pkg = e.getKey();
            if (pkg.equals(UsageCollector.PRIVATE_APP)) continue;
            int hits = 0;
            for (DayUsage u : history) {
                for (DayUsage.Session s : u.sessions) {
                    int h = java.time.Instant.ofEpochMilli(s.startMs).atZone(zone).getHour();
                    if (s.pkg.equals(pkg) && h == hourStart) {
                        hits++;
                        break;
                    }
                }
            }
            String name = AppPalette.label(ctx, pkg);
            out.add(new Odd("NEXT HOUR", "You open " + name + " before " + String.format("%02d:00", (hourStart + 1) % 24),
                    hits, n, hits + " of the last " + n + " days at this hour", pkg));
        }

        // Tonight
        int late = 0, longQuiet = 0, quietDays = 0, charged = 0;
        for (DayUsage u : history) {
            for (DayUsage.Session s : u.sessions) {
                int h = java.time.Instant.ofEpochMilli(s.startMs).atZone(zone).getHour();
                if (h < 4) {
                    late++;
                    break;
                }
            }
            DayUsage.Quiet q = quiets.get(u.day);
            if (q != null) {
                quietDays++;
                if (q.ms() >= 7 * 3600_000L) longQuiet++;
                if (q.charging) charged++;
            }
        }
        out.add(new Odd("TONIGHT", "You're on your phone after midnight", late, n, late + " of the last " + n + " nights", null));
        if (quietDays >= MIN_DAYS) {
            out.add(new Odd("TONIGHT", "At least 7 hours device-free", longQuiet, quietDays, longQuiet + " of " + quietDays + " nights", null));
            if (charged > 0) {
                out.add(new Odd("TONIGHT", "Phone charges through the night", charged, quietDays, charged + " of " + quietDays + " nights", null));
            }
        }

        // Tomorrow: same weekday first, all days as fallback
        LocalDate tomorrow = today.plusDays(1);
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            String pkg = top.get(i).getKey();
            if (pkg.equals(UsageCollector.PRIVATE_APP)) continue;
            int hits = 0, sameDay = 0, sameHits = 0;
            for (DayUsage u : history) {
                boolean used = u.appMs.containsKey(pkg);
                if (used) hits++;
                if (u.day.getDayOfWeek() == tomorrow.getDayOfWeek()) {
                    sameDay++;
                    if (used) sameHits++;
                }
            }
            String name = AppPalette.label(ctx, pkg);
            long avg = top.get(i).getValue() / n;
            out.add(new Odd("TOMORROW", "You open " + name, hits, n,
                    hits + " of " + n + " days · usually " + Fmt.shortDuration(avg) + "/day"
                            + (sameDay >= 2 ? " · " + sameHits + " of " + sameDay + " " + tomorrow.getDayOfWeek().toString().charAt(0)
                            + tomorrow.getDayOfWeek().toString().substring(1, 3).toLowerCase() + "s" : ""), pkg));
        }
        long medianScreen = median(history);
        int heavy = 0;
        for (DayUsage u : history) if (u.screenMs > medianScreen) heavy++;
        out.add(new Odd("TOMORROW", "More than " + Fmt.shortDuration(medianScreen) + " on screen", heavy, n,
                "your median day is " + Fmt.shortDuration(medianScreen), null));
        return out;
    }

    private static long median(List<DayUsage> days) {
        List<Long> v = new ArrayList<>();
        for (DayUsage u : days) v.add(u.screenMs);
        v.sort(Long::compare);
        return v.get(v.size() / 2);
    }
}
