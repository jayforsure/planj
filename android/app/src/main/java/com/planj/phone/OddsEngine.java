package com.planj.phone;

import android.content.Context;

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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Forecasts about tomorrow that label themselves the morning after, from the person's own
 * days. A lever narrows a forecast only if replaying history shows it would have beaten the
 * plain base rate. Every forecast is written down before the day and scored after.
 */
final class OddsEngine {
    static final int HISTORY_DAYS = 45;
    static final int MIN_HISTORY = 5;
    static final int MIN_SIDE = 4;

    /** One day, as numbers. Nulls mean "not measurable that day". */
    static final class Day {
        final LocalDate date;
        double screenH, socialH, lateH, unlocks, firstUseHour = -1;
        Double quietH, quietStartHour;
        boolean charged;

        Day(LocalDate date) {
            this.date = date;
        }
    }

    interface Label {
        Boolean of(Day target, List<Day> before);
    }

    static final class Outcome {
        final String id, question, resolved;
        final Label label;

        Outcome(String id, String question, String resolved, Label label) {
            this.id = id;
            this.question = question;
            this.resolved = resolved;
            this.label = label;
        }
    }

    static final class Lever {
        final String id, whenTrue;
        final Label test; // evaluated on the evening's day, with the days before it

        Lever(String id, String whenTrue, Label test) {
            this.id = id;
            this.whenTrue = whenTrue;
            this.test = test;
        }
    }

    static final class Forecast {
        final Outcome outcome;
        final double prob, base;
        final int n, sideK, sideN;
        final String leverText; // null when the base rate stands alone

        Forecast(Outcome outcome, double prob, double base, int n, String leverText, int sideK, int sideN) {
            this.outcome = outcome;
            this.prob = prob;
            this.base = base;
            this.n = n;
            this.leverText = leverText;
            this.sideK = sideK;
            this.sideN = sideN;
        }
    }

    static final class Record {
        int n, hits, baseHits;
        double brier, baseBrier;

        void add(double p, double base, boolean y) {
            n++;
            hits += (p >= 0.5) == y ? 1 : 0;
            baseHits += (base >= 0.5) == y ? 1 : 0;
            brier += (p - (y ? 1 : 0)) * (p - (y ? 1 : 0));
            baseBrier += (base - (y ? 1 : 0)) * (base - (y ? 1 : 0));
        }
    }

    static final List<Outcome> OUTCOMES = List.of(
            new Outcome("off_by_1am", "Off all devices by 1am tonight", "off by 1am",
                    (t, b) -> t.quietStartHour == null ? null : t.quietStartHour >= 20 || t.quietStartHour <= 1.0),
            new Outcome("quiet_7h", "At least 7 hours device-free tonight", "7h+ device-free",
                    (t, b) -> t.quietH == null ? null : t.quietH >= 7),
            new Outcome("heavy_screen", "Tomorrow is a heavier-than-usual screen day", "heavy screen day",
                    (t, b) -> {
                        List<Double> past = new ArrayList<>();
                        for (Day d : b) past.add(d.screenH);
                        return past.size() < MIN_HISTORY ? null : t.screenH > median(past);
                    }),
            new Outcome("social_2h", "Over 2 hours of social apps tomorrow", "2h+ social",
                    (t, b) -> t.socialH > 2),
            new Outcome("up_by_8", "First phone use before 08:00 tomorrow", "up by 8",
                    (t, b) -> t.firstUseHour < 0 ? null : t.firstUseHour < 8)
    );

    static final List<Lever> LEVERS = List.of(
            new Lever("short_night", "after a night under 7h device-free", (d, b) -> d.quietH == null ? null : d.quietH < 7),
            new Lever("late_phone", "after phone use between midnight and 5am", (d, b) -> d.lateH > 0.5),
            new Lever("social_heavy", "on a day with over 2h of social apps", (d, b) -> d.socialH > 2),
            new Lever("many_unlocks", "on a day with more unlocks than usual", (d, b) -> {
                List<Double> past = new ArrayList<>();
                for (Day x : b) past.add(x.unlocks);
                return past.size() < MIN_HISTORY ? null : d.unlocks > median(past);
            }),
            new Lever("charged", "on a night the phone was charging", (d, b) -> d.charged),
            new Lever("weekend_next", "when tomorrow is a weekend day",
                    (d, b) -> d.date.getDayOfWeek().getValue() >= 5) // Fri or Sat evening
    );

    private OddsEngine() {}

    // ----- history from the phone's own files -----

    static TreeMap<LocalDate, Day> history(Context ctx) {
        TreeMap<LocalDate, Day> out = new TreeMap<>();
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        for (int i = HISTORY_DAYS; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            DayUsage u = DayUsage.load(ctx, date);
            if (u.screenMs == 0 && u.appMs.isEmpty()) continue;
            Day d = new Day(date);
            d.screenH = u.screenMs / 3600000.0;
            d.lateH = u.lateNightMs / 3600000.0;
            d.unlocks = u.unlocks;
            long social = 0;
            for (Map.Entry<String, Long> e : u.appMs.entrySet()) if (isSocial(e.getKey())) social += e.getValue();
            d.socialH = social / 3600000.0;
            if (!u.sessions.isEmpty()) {
                long first = Long.MAX_VALUE;
                for (DayUsage.Session s : u.sessions) first = Math.min(first, s.startMs);
                java.time.ZonedDateTime z = Instant.ofEpochMilli(first).atZone(zone);
                d.firstUseHour = z.getHour() + z.getMinute() / 60.0;
            }
            DayUsage.Quiet q = DayUsage.quiet(ctx, date);
            if (q != null) {
                d.quietH = q.ms() / 3600000.0;
                java.time.ZonedDateTime z = Instant.ofEpochMilli(q.startMs).atZone(zone);
                d.quietStartHour = z.getHour() + z.getMinute() / 60.0;
                d.charged = q.charging;
            }
            out.put(date, d);
        }
        return out;
    }

    private static boolean isSocial(String pkg) {
        String p = pkg.toLowerCase();
        for (String s : new String[]{"instagram", "xingin", "twitter", "tiktok", "facebook", "threads", "linkedin", "snapchat", "reddit"})
            if (p.contains(s)) return true;
        return false;
    }

    static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get(s.size() / 2);
    }

    private static List<Day> before(TreeMap<LocalDate, Day> hist, LocalDate date) {
        return new ArrayList<>(hist.headMap(date, false).values());
    }

    // ----- forecasting -----

    /** A lever is used only if, leaving each day out, narrowing by it would have scored better than the base rate. */
    private static boolean earnsItsKeep(List<boolean[]> sides) { // {side, y}
        double leverErr = 0, baseErr = 0;
        for (int i = 0; i < sides.size(); i++) {
            int sameK = 0, sameN = 0, allK = 0, allN = 0;
            for (int j = 0; j < sides.size(); j++) {
                if (j == i) continue;
                boolean[] r = sides.get(j);
                allN++;
                if (r[1]) allK++;
                if (r[0] == sides.get(i)[0]) {
                    sameN++;
                    if (r[1]) sameK++;
                }
            }
            double y = sides.get(i)[1] ? 1 : 0;
            double pl = (sameK + 1.0) / (sameN + 2), pb = (allK + 1.0) / (allN + 2);
            leverErr += (pl - y) * (pl - y);
            baseErr += (pb - y) * (pb - y);
        }
        return leverErr < baseErr;
    }

    static List<Forecast> forecast(TreeMap<LocalDate, Day> hist, LocalDate evening) {
        Day today = hist.get(evening);
        List<Forecast> out = new ArrayList<>();
        if (today == null) return out;
        List<Day> pastDays = before(hist, evening);
        for (Outcome oc : OUTCOMES) {
            List<Object[]> rows = new ArrayList<>(); // {Day evening, List<Day> before, Boolean y}
            for (Map.Entry<LocalDate, Day> e : hist.entrySet()) {
                LocalDate next = e.getKey().plusDays(1);
                Day nxt = hist.get(next);
                if (nxt == null || !next.isBefore(evening)) continue;
                Boolean y = oc.label.of(nxt, before(hist, next));
                if (y != null) rows.add(new Object[]{e.getValue(), before(hist, e.getKey()), y});
            }
            if (rows.size() < MIN_HISTORY) continue;
            int k = 0;
            for (Object[] r : rows) if ((Boolean) r[2]) k++;
            int n = rows.size();
            double base = (double) k / n;

            double bestGap = -1;
            Lever bestLever = null;
            boolean bestSide = false;
            int bestK = 0, bestN = 0;
            for (Lever lv : LEVERS) {
                Boolean sideToday = lv.test.of(today, pastDays);
                if (sideToday == null) continue;
                List<boolean[]> sides = new ArrayList<>();
                int[][] split = new int[2][2]; // [side][k,n]
                for (Object[] r : rows) {
                    @SuppressWarnings("unchecked")
                    Boolean s = lv.test.of((Day) r[0], (List<Day>) r[1]);
                    if (s == null) continue;
                    boolean y = (Boolean) r[2];
                    sides.add(new boolean[]{s, y});
                    split[s ? 1 : 0][1]++;
                    if (y) split[s ? 1 : 0][0]++;
                }
                if (split[0][1] < MIN_SIDE || split[1][1] < MIN_SIDE || !earnsItsKeep(sides)) continue;
                double gap = Math.abs((double) split[1][0] / split[1][1] - (double) split[0][0] / split[0][1]);
                if (gap > bestGap) {
                    bestGap = gap;
                    bestLever = lv;
                    bestSide = sideToday;
                    bestK = split[sideToday ? 1 : 0][0];
                    bestN = split[sideToday ? 1 : 0][1];
                }
            }
            if (bestLever != null) {
                out.add(new Forecast(oc, (bestK + 1.0) / (bestN + 2), base, n,
                        bestSide ? bestLever.whenTrue : "not " + bestLever.whenTrue, bestK, bestN));
            } else {
                out.add(new Forecast(oc, (k + 1.0) / (n + 2), base, n, null, k, n));
            }
        }
        return out;
    }

    static Boolean resolve(TreeMap<LocalDate, Day> hist, LocalDate target, String outcomeId) {
        Day t = hist.get(target);
        if (t == null) return null;
        for (Outcome oc : OUTCOMES) if (oc.id.equals(outcomeId)) return oc.label.of(t, before(hist, target));
        return null;
    }

    static Map<String, Record> backtest(TreeMap<LocalDate, Day> hist) {
        Map<String, Record> out = new LinkedHashMap<>();
        for (LocalDate evening : hist.keySet()) {
            LocalDate target = evening.plusDays(1);
            if (!hist.containsKey(target)) continue;
            for (Forecast fc : forecast(hist, evening)) {
                Boolean y = resolve(hist, target, fc.outcome.id);
                if (y != null) out.computeIfAbsent(fc.outcome.id, k -> new Record()).add(fc.prob, fc.base, y);
            }
        }
        return out;
    }

    // ----- the written record -----

    private static File ledger(Context ctx) {
        return new File(ctx.getFilesDir(), "forecasts.jsonl");
    }

    /** Records tonight's forecasts unless the same target day already has them: a forecast, once made, stands. */
    static synchronized void record(Context ctx, LocalDate evening, List<Forecast> forecasts) {
        String target = evening.plusDays(1).toString();
        List<JSONObject> all = readLedger(ctx);
        for (JSONObject o : all) if (target.equals(o.optString("target"))) return;
        try (OutputStream out = new FileOutputStream(ledger(ctx), true)) {
            for (Forecast fc : forecasts) {
                JSONObject o = new JSONObject().put("target", target).put("outcome", fc.outcome.id)
                        .put("made", Instant.now().toString()).put("prob", fc.prob).put("base", fc.base).put("n", fc.n);
                if (fc.leverText != null) o.put("lever", fc.leverText);
                out.write((o + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException e) {
            // best effort
        }
    }

    /** Fills in what happened for forecasts whose day now has data. Returns the live track record. */
    static synchronized Map<String, Record> settle(Context ctx, TreeMap<LocalDate, Day> hist) {
        List<JSONObject> all = readLedger(ctx);
        boolean changed = false;
        Map<String, Record> live = new LinkedHashMap<>();
        for (JSONObject o : all) {
            try {
                if (!o.has("actual")) {
                    Boolean y = resolve(hist, LocalDate.parse(o.getString("target")), o.getString("outcome"));
                    if (y != null && LocalDate.parse(o.getString("target")).isBefore(LocalDate.now())) {
                        o.put("actual", y);
                        changed = true;
                    }
                }
                if (o.has("actual")) {
                    live.computeIfAbsent(o.getString("outcome"), k -> new Record())
                            .add(o.getDouble("prob"), o.getDouble("base"), o.getBoolean("actual"));
                }
            } catch (JSONException e) {
                // skip a bad line
            }
        }
        if (changed) {
            try (OutputStream out = new FileOutputStream(ledger(ctx), false)) {
                for (JSONObject o : all) out.write((o + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // keep the in-memory answer
            }
        }
        return live;
    }

    private static List<JSONObject> readLedger(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(ledger(ctx), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                try {
                    out.add(new JSONObject(line));
                } catch (JSONException e) {
                    // skip
                }
            }
        } catch (IOException e) {
            // none yet
        }
        return out;
    }
}
