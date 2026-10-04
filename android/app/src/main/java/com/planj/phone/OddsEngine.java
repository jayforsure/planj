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
    static final class Day implements Routines.DayBlocks {
        final LocalDate date;
        double screenH, socialH, lateH, unlocks, firstUseHour = -1;
        Double quietH, quietStartHour;
        boolean charged;
        List<int[]> free;           // phone untouched, within the day
        List<int[]> away;           // off the home Wi-Fi; null until network samples exist
        Map<String, List<int[]>> at; // time at each place other than home; null when places are off
        PcFocus pc;                  // null when the PC's focus that day can't be known
        Integer nextClassMin;        // the next day's first class (minutes), -1 none, null not known
        Integer nextPlanMin;         // the next day's first timed calendar event, -1 none, null when Calendar isn't connected

        Day(LocalDate date) {
            this.date = date;
        }

        @Override
        public LocalDate date() {
            return date;
        }

        @Override
        public List<int[]> blocks(Routines.Kind kind) {
            return kind == Routines.Kind.FREE ? free : away;
        }

        @Override
        public List<int[]> placeBlocks(String place) {
            return at == null ? null : at.getOrDefault(place, Collections.emptyList());
        }

        @Override
        public java.util.Set<String> places() {
            return at == null ? Collections.emptySet() : at.keySet();
        }
    }

    /** The days, and every first class TAR UMT marked (empty when it isn't connected). */
    static final class History extends TreeMap<LocalDate, Day> {
        List<TarcAttendance.ClassDay> classes = Collections.emptyList();
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
        final String id, whenTrue, whenFalse; // short enough to sit on one line under a forecast
        final Label test; // evaluated on the evening's day, with the days before it

        Lever(String id, String whenTrue, String whenFalse, Label test) {
            this.id = id;
            this.whenTrue = whenTrue;
            this.whenFalse = whenFalse;
            this.test = test;
        }
    }

    static final class Forecast {
        final Outcome outcome;
        final double prob, base;
        final int n, sideK, sideN;
        final String leverText; // null when the base rate stands alone
        String leverId;          // which signal moved it, and which side of it today is on
        boolean leverSide;
        int otherK, otherN;      // the same history on the other side of that signal

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

    static final String FOCUS = "pc_focus_2h";
    static final String CLASS = "class_first";
    static final Outcome CLASS_OUTCOME = new Outcome(CLASS, "Make your first class", "made your first class", (t, b) -> null);
    private static final int RECENT_DAYS = 21, RECENT_MAX = 6, RECENT_MIN = 3;

    static final List<Outcome> OUTCOMES = List.of(
            new Outcome("off_by_1am", "Off devices by 1am tonight", "off by 1am",
                    (t, b) -> t.quietStartHour == null ? null : t.quietStartHour >= 20 || t.quietStartHour <= 1.0),
            new Outcome("quiet_7h", "7h+ device-free tonight", "7h+ device-free",
                    (t, b) -> t.quietH == null ? null : t.quietH >= 7),
            new Outcome("heavy_screen", "Heavier screen day", "heavy screen day",
                    (t, b) -> {
                        List<Double> past = new ArrayList<>();
                        for (Day d : b) past.add(d.screenH);
                        return past.size() < MIN_HISTORY ? null : t.screenH > median(past);
                    }),
            new Outcome("social_2h", "2h+ on social apps", "2h+ social",
                    (t, b) -> t.socialH > 2),
            new Outcome("up_by_8", "Up by 8", "up by 8",
                    (t, b) -> t.firstUseHour < 0 ? null : t.firstUseHour < 8),
            new Outcome(FOCUS, "2h+ focus on your PC", "2h+ PC focus",
                    (t, b) -> t.pc == null ? null : t.pc.focusH >= PcFocus.GOAL_H)
    );

    static final List<Lever> LEVERS = List.of(
            new Lever("short_night", "after a short night", "after a full night", (d, b) -> d.quietH == null ? null : d.quietH < 7),
            new Lever("late_phone", "after phone past midnight", "after no phone past midnight", (d, b) -> d.lateH > 0.5),
            new Lever("social_heavy", "after 2h+ social", "after under 2h social", (d, b) -> d.socialH > 2),
            new Lever("many_unlocks", "after many unlocks", "after few unlocks", (d, b) -> {
                List<Double> past = new ArrayList<>();
                for (Day x : b) past.add(x.unlocks);
                return past.size() < MIN_HISTORY ? null : d.unlocks > median(past);
            }),
            new Lever("pc_focus_day", "after a focused PC day", "after little PC focus",
                    (d, b) -> d.pc == null ? null : d.pc.focusH >= PcFocus.GOAL_H),
            new Lever("pc_watch_late", "after watching past 11pm", "after no late watching",
                    (d, b) -> d.pc == null ? null : d.pc.lateWatchMin >= 15),
            new Lever("pc_watch_heavy", "after 1h+ watching", "after under 1h watching",
                    (d, b) -> d.pc == null ? null : d.pc.watchH > 1),
            new Lever("out_at_place", "after a day out", "after a day at home", (d, b) -> d.at == null ? null : !d.at.isEmpty()),
            new Lever("class_morning", "before a morning class", "before no morning class",
                    (d, b) -> d.nextClassMin == null ? null : d.nextClassMin >= 0 && d.nextClassMin < 11 * 60),
            new Lever("early_plans", "before plans before 10am", "before a free morning",
                    (d, b) -> d.nextPlanMin == null ? null : d.nextPlanMin >= 0 && d.nextPlanMin < 10 * 60),
            new Lever("charged", "charging overnight", "not charging overnight", (d, b) -> d.charged),
            new Lever("weekend_next", "before a weekend day", "before a weekday",
                    (d, b) -> d.date.getDayOfWeek().getValue() >= 5) // Fri or Sat evening
    );

    private OddsEngine() {}

    // ----- history from the phone's own files -----

    static History history(Context ctx) {
        History out = new History();
        try {
            out.classes = TarcAttendance.days(ctx);
        } catch (RuntimeException e) {
            // forecasts without the class one
        }
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        List<DayUsage> usages = new ArrayList<>();
        for (int i = HISTORY_DAYS; i >= 0; i--) usages.add(DayUsage.load(ctx, today.minusDays(i)));
        String home = homeOf(usages, zone, true);
        String markedHome = Places.homeId(ctx);
        String homePlace = markedHome != null ? markedHome : homeOf(usages, zone, false);
        String carried = null; // the network state at the start of each day
        String carriedPlace = null;
        boolean pcSorting = false; // the PC has sorted focus from watching on some earlier day
        boolean calendar = Agenda.allowed(ctx);
        for (DayUsage u : usages) {
            LocalDate date = u.day;
            List<int[]> away = awayBlocks(u, carried, home, zone);
            if (!u.net.isEmpty()) carried = u.net.get(u.net.size() - 1)[1];
            Map<String, List<int[]>> at = placeBlocks(u, carriedPlace, homePlace, zone);
            if (!u.place.isEmpty()) carriedPlace = u.place.get(u.place.size() - 1)[1];
            PcFocus reported = null;
            List<PcDays.Seg> segs = PcDays.load(ctx, date);
            if (segs != null) {
                reported = new PcFocus();
                for (PcDays.Seg sg : segs) reported.add(sg.start, sg.end, sg.cat);
            }
            PcFocus pc = PcFocus.count(reported, pcSorting, date.isBefore(today.minusDays(1)));
            if (reported != null && reported.sorted()) pcSorting = true;
            if (u.screenMs == 0 && u.appMs.isEmpty()) continue;
            Day d = new Day(date);
            d.pc = pc;
            d.nextClassMin = TarcTimetable.firstClass(ctx, date.plusDays(1));
            if (calendar) {
                Agenda.Event first = Agenda.firstTimed(Agenda.on(ctx, date.plusDays(1)));
                d.nextPlanMin = first == null ? -1 : minuteOfDay(first.startMs, date.plusDays(1), zone);
            }
            d.away = away;
            d.at = at;
            List<int[]> used = new ArrayList<>();
            for (long[] on : u.screenOn) used.add(new int[]{minuteOfDay(on[0], date, zone), minuteOfDay(on[1], date, zone)});
            d.free = Routines.gaps(used, Routines.Kind.FREE);
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

    private static int minuteOfDay(long ms, LocalDate date, ZoneId zone) {
        long start = date.atStartOfDay(zone).toInstant().toEpochMilli();
        return (int) Math.max(0, Math.min(24 * 60, (ms - start) / 60000));
    }

    /** Home is where the phone is at 3am most often: a Wi-Fi network, or a place. */
    private static String homeOf(List<DayUsage> usages, ZoneId zone, boolean network) {
        Map<String, Integer> votes = new java.util.HashMap<>();
        String state = null;
        for (DayUsage u : usages) {
            long three = u.day.atTime(3, 0).atZone(zone).toInstant().toEpochMilli();
            String at3 = state;
            for (String[] ev : network ? u.net : u.place) {
                if (Long.parseLong(ev[0]) <= three) at3 = ev[1];
                state = ev[1];
            }
            if (at3 != null && (!network || at3.startsWith("wifi:"))) votes.merge(at3, 1, Integer::sum);
        }
        String best = null;
        for (Map.Entry<String, Integer> e : votes.entrySet()) if (best == null || e.getValue() > votes.get(best)) best = e.getKey();
        return best;
    }

    /** Stretches of the day off the home Wi-Fi. Null when there is nothing to go on yet. */
    private static List<int[]> awayBlocks(DayUsage u, String carried, String home, ZoneId zone) {
        if (home == null || (carried == null && u.net.isEmpty())) return null;
        List<int[]> out = new ArrayList<>();
        String state = carried;
        int since = 0;
        for (String[] ev : u.net) {
            int m = minuteOfDay(Long.parseLong(ev[0]), u.day, zone);
            if (state != null && !state.equals(home)) out.add(new int[]{since, m});
            state = ev[1];
            since = m;
        }
        int end = u.day.equals(LocalDate.now())
                ? minuteOfDay(System.currentTimeMillis(), u.day, zone) : 24 * 60;
        if (state != null && !state.equals(home)) out.add(new int[]{since, end});
        return Routines.clean(out, Routines.Kind.AWAY);
    }

    /** Time at each place other than home. A place holds until the next place sample. */
    private static Map<String, List<int[]>> placeBlocks(DayUsage u, String carried, String home, ZoneId zone) {
        if (carried == null && u.place.isEmpty()) return null;
        Map<String, List<int[]>> raw = new java.util.HashMap<>();
        String state = carried;
        int since = 0;
        for (String[] ev : u.place) {
            int m = minuteOfDay(Long.parseLong(ev[0]), u.day, zone);
            if (state != null && !state.equals(home)) raw.computeIfAbsent(state, k -> new ArrayList<>()).add(new int[]{since, m});
            state = ev[1];
            since = m;
        }
        int end = u.day.equals(LocalDate.now()) ? minuteOfDay(System.currentTimeMillis(), u.day, zone) : 24 * 60;
        if (state != null && !state.equals(home)) raw.computeIfAbsent(state, k -> new ArrayList<>()).add(new int[]{since, end});
        Map<String, List<int[]>> out = new java.util.HashMap<>();
        for (Map.Entry<String, List<int[]>> e : raw.entrySet()) {
            List<int[]> blocks = Routines.clean(e.getValue(), Routines.Kind.AT);
            if (!blocks.isEmpty()) out.put(e.getKey(), blocks);
        }
        return out;
    }

    // ----- routines as outcomes -----

    /** The routines seen up to and including `evening`, as outcomes that label themselves. */
    static List<Outcome> routineOutcomes(TreeMap<LocalDate, Day> hist, LocalDate evening) {
        List<Outcome> out = new ArrayList<>();
        for (Routines.Routine r : Routines.find(new ArrayList<>(hist.headMap(evening, true).values()))) {
            out.add(routineOutcome(r));
        }
        return out;
    }

    static Outcome routineOutcome(Routines.Routine r) {
        String name = r.kind == Routines.Kind.AT ? Places.label(r.place) : r.kind.defaultName;
        return new Outcome(r.id(), name + " · " + r.days() + " " + r.window(),
                name.toLowerCase(java.util.Locale.ROOT) + " " + r.window(), (t, b) -> r.happened(t));
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

    static List<Forecast> forecast(History hist, LocalDate evening) {
        Day today = hist.get(evening);
        List<Forecast> out = new ArrayList<>();
        if (today == null) return out;
        List<Day> pastDays = before(hist, evening);
        List<Outcome> outcomes = new ArrayList<>(OUTCOMES);
        for (Outcome oc : routineOutcomes(hist, evening)) {
            Routines.Routine r = Routines.Routine.parse(oc.id);
            if (r != null && r.appliesTo(evening.plusDays(1))) outcomes.add(oc);
        }
        for (Outcome oc : outcomes) {
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
            boolean routine = oc.id.startsWith("rt_");
            // a question that always has the same answer is not worth asking, except whether a
            // routine will hold: "92% you'll be out on Tuesday evening" is the point
            if (k == 0 || (k == n && !routine)) continue;

            Forecast f = withLever(oc, base, n, bestLever(rows, today, pastDays));
            out.add(f != null ? f : new Forecast(oc, (k + 1.0) / (n + 2), base, n, null, k, n));
        }
        if (today.nextClassMin != null && today.nextClassMin >= 0) { // a class tomorrow
            Forecast c = classForecast(hist, evening);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** The signal that splits these days most, among those that would have beaten the plain rate. */
    private static final class Split {
        Lever lever;
        boolean side;
        int k, n, otherK, otherN;
    }

    /** rows: {Day evening, List<Day> before, Boolean y}. Null when no signal earns its keep. */
    private static Split bestLever(List<Object[]> rows, Day today, List<Day> pastDays) {
        double bestGap = -1;
        Split best = null;
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
                best = new Split();
                best.lever = lv;
                best.side = sideToday;
                best.k = split[sideToday ? 1 : 0][0];
                best.n = split[sideToday ? 1 : 0][1];
                best.otherK = split[sideToday ? 0 : 1][0];
                best.otherN = split[sideToday ? 0 : 1][1];
            }
        }
        return best;
    }

    private static Forecast withLever(Outcome oc, double base, int n, Split s) {
        if (s == null) return null;
        Forecast f = new Forecast(oc, (s.k + 1.0) / (s.n + 2), base, n, s.side ? s.lever.whenTrue : s.lever.whenFalse, s.k, s.n);
        f.leverId = s.lever.id;
        f.leverSide = s.side;
        f.otherK = s.otherK;
        f.otherN = s.otherN;
        return f;
    }

    // ----- tonight's move -----

    /** Something you can do tonight: a test of a day's night (which belongs to that day) or of the evening before it. */
    interface Night {
        Boolean of(Day day, Day evening); // evening is null when that day isn't known
    }

    static final class MoveDef {
        final String id;
        final Night did;

        MoveDef(String id, Night did) {
            this.id = id;
            this.did = did;
        }
    }

    static final List<MoveDef> MOVES = List.of(
            new MoveDef("bed_23", (d, e) -> offBy(d, -1)),
            new MoveDef("bed_00", (d, e) -> offBy(d, 0)),
            new MoveDef("bed_01", (d, e) -> offBy(d, 1)),
            new MoveDef("bed_02", (d, e) -> offBy(d, 2)),
            new MoveDef("full_night", (d, e) -> d.quietH == null ? null : d.quietH >= 7),
            new MoveDef("no_late_watch", (d, e) -> e == null || e.pc == null ? null : e.pc.lateWatchMin < 15));

    /** Phone down for the night by this hour (-1 is 11pm, 0 midnight, 1 1am). */
    private static Boolean offBy(Day d, int hour) {
        if (d.quietStartHour == null) return null;
        double h = d.quietStartHour >= 12 ? d.quietStartHour - 24 : d.quietStartHour;
        return h <= hour;
    }

    /** A move and what followed: the goal reached on k of n days after nights you did it, otherK of otherN after the rest. */
    static final class Move {
        final String id;
        final int k, n, otherK, otherN;

        Move(String id, int k, int n, int otherK, int otherN) {
            this.id = id;
            this.k = k;
            this.n = n;
            this.otherK = otherK;
            this.otherN = otherN;
        }

        double with() {
            return (k + 1.0) / (n + 2);
        }

        double without() {
            return (otherK + 1.0) / (otherN + 2);
        }
    }

    /**
     * What to do tonight for a goal: the move after which you reached it most more often, from
     * your own days. It needs MIN_SIDE nights each way, must have predicted better than the plain
     * rate (leaving each day out), and must lift your chance by at least 10 points. Null until one does.
     */
    static Move move(History hist, String outcomeId, boolean good) {
        LocalDate today = LocalDate.now();
        List<Object[]> days = new ArrayList<>(); // {Day, Day evening, Boolean reached}
        for (Day d : hist.values()) {
            if (!d.date.isBefore(today)) continue; // today isn't over
            Boolean y = resolve(hist, d.date, outcomeId);
            if (y != null) days.add(new Object[]{d, hist.get(d.date.minusDays(1)), y == good});
        }
        Move best = null;
        double bestGain = 0.10;
        for (MoveDef m : MOVES) {
            if (m.id.equals("full_night") && outcomeId.equals("quiet_7h")) continue; // that's the goal itself
            List<boolean[]> sides = new ArrayList<>();
            int[][] split = new int[2][2]; // [did][reached, nights]
            for (Object[] r : days) {
                Boolean did = m.did.of((Day) r[0], (Day) r[1]);
                if (did == null) continue;
                boolean reached = (Boolean) r[2];
                sides.add(new boolean[]{did, reached});
                split[did ? 1 : 0][1]++;
                if (reached) split[did ? 1 : 0][0]++;
            }
            if (split[0][1] < MIN_SIDE || split[1][1] < MIN_SIDE || !earnsItsKeep(sides)) continue;
            Move mv = new Move(m.id, split[1][0], split[1][1], split[0][0], split[0][1]);
            if (mv.with() - mv.without() > bestGain) {
                bestGain = mv.with() - mv.without();
                best = mv;
            }
        }
        return best;
    }

    // ----- your first class, from TAR UMT's attendance -----

    /**
     * Whether you make the first class the day after `evening`. Attendance comes in streaks, so
     * your last few class days count most, pulled toward your usual rate; that is used only once
     * replaying it has beaten the usual rate. An evening signal from this phone or PC takes over
     * when it earns its keep, as it does for every other forecast.
     */
    private static Forecast classForecast(History hist, LocalDate evening) {
        List<TarcAttendance.ClassDay> past = new ArrayList<>();
        for (TarcAttendance.ClassDay c : hist.classes) if (!c.date.isAfter(evening)) past.add(c);
        int n = past.size(), k = 0;
        if (n < MIN_HISTORY) return null;
        for (TarcAttendance.ClassDay c : past) if (c.made) k++;
        double base = (double) k / n, usual = (k + 1.0) / (n + 2);

        Day today = hist.get(evening);
        if (today != null) {
            List<Object[]> rows = new ArrayList<>();
            for (TarcAttendance.ClassDay c : past) {
                LocalDate eve = c.date.minusDays(1);
                Day d = hist.get(eve);
                if (d != null) rows.add(new Object[]{d, before(hist, eve), c.made});
            }
            Forecast f = withLever(CLASS_OUTCOME, base, n, bestLever(rows, today, before(hist, evening)));
            if (f != null) return f;
        }

        int[] recent = recent(past, evening.plusDays(1));
        if (recent == null || !recentEarnsItsKeep(past)) return new Forecast(CLASS_OUTCOME, usual, base, n, null, k, n);
        Forecast f = new Forecast(CLASS_OUTCOME, (recent[0] + 2 * usual) / (recent[1] + 2), base, n,
                "made " + recent[0] + " of the last " + recent[1], recent[0], recent[1]);
        f.leverId = "recent";
        f.leverSide = true;
        f.otherK = k;
        f.otherN = n;
        return f;
    }

    /** {made, of} over your last few class days before `target`; null when too few were lately. */
    private static int[] recent(List<TarcAttendance.ClassDay> past, LocalDate target) {
        int made = 0, of = 0;
        for (int i = past.size() - 1; i >= 0 && of < RECENT_MAX; i--) {
            TarcAttendance.ClassDay c = past.get(i);
            if (c.date.isBefore(target.minusDays(RECENT_DAYS))) break; // after a break, last term says little
            of++;
            if (c.made) made++;
        }
        return of < RECENT_MIN ? null : new int[]{made, of};
    }

    /** Replaying the class days so far: would leaning on the last few have beaten the usual rate? */
    private static boolean recentEarnsItsKeep(List<TarcAttendance.ClassDay> past) {
        double recentErr = 0, usualErr = 0;
        int compared = 0, k = 0;
        for (int i = 0; i < past.size(); i++) {
            if (i >= MIN_HISTORY) {
                int[] r = recent(past.subList(0, i), past.get(i).date);
                if (r != null) {
                    double usual = (k + 1.0) / (i + 2), pr = (r[0] + 2 * usual) / (r[1] + 2), y = past.get(i).made ? 1 : 0;
                    recentErr += (pr - y) * (pr - y);
                    usualErr += (usual - y) * (usual - y);
                    compared++;
                }
            }
            if (past.get(i).made) k++;
        }
        return compared >= MIN_HISTORY && recentErr < usualErr;
    }

    /** Days so far on which an outcome could be scored the morning after; forecasts start at MIN_HISTORY. */
    static int daysFor(History hist, LocalDate evening, String outcomeId) {
        int n = 0;
        if (outcomeId.equals(CLASS)) {
            for (TarcAttendance.ClassDay c : hist.classes) if (!c.date.isAfter(evening)) n++;
            return n;
        }
        for (LocalDate d : hist.keySet()) {
            LocalDate next = d.plusDays(1);
            if (next.isBefore(evening) && hist.containsKey(next) && resolve(hist, next, outcomeId) != null) n++;
        }
        return n;
    }

    static Boolean resolve(History hist, LocalDate target, String outcomeId) {
        if (outcomeId.equals(CLASS)) { // answered once TAR UMT has marked that day's first class
            for (TarcAttendance.ClassDay c : hist.classes) if (c.date.equals(target)) return c.made;
            return null;
        }
        Day t = hist.get(target);
        if (t == null) return null;
        for (Outcome oc : OUTCOMES) if (oc.id.equals(outcomeId)) return oc.label.of(t, before(hist, target));
        Routines.Routine r = Routines.Routine.parse(outcomeId);
        return r == null ? null : r.happened(t);
    }

    static Map<String, Record> backtest(History hist) {
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

    /** Records tonight's forecasts; one already made for the same day and question stands. */
    static synchronized void record(Context ctx, LocalDate evening, List<Forecast> forecasts) {
        String target = evening.plusDays(1).toString();
        java.util.Set<String> made = new java.util.HashSet<>();
        for (JSONObject o : readLedger(ctx)) if (target.equals(o.optString("target"))) made.add(o.optString("outcome"));
        try (OutputStream out = new FileOutputStream(ledger(ctx), true)) {
            for (Forecast fc : forecasts) {
                if (made.contains(fc.outcome.id)) continue;
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
    /**
     * Forecasts that have been checked, oldest first: {target date, outcome id, prob, actual}.
     * For one outcome when outcomeId is given, else all.
     */
    static synchronized List<Object[]> checked(Context ctx, String outcomeId) {
        List<Object[]> out = new ArrayList<>();
        for (JSONObject o : readLedger(ctx)) {
            if (!o.has("actual") || (outcomeId != null && !outcomeId.equals(o.optString("outcome")))) continue;
            out.add(new Object[]{o.optString("target"), o.optString("outcome"), o.optDouble("prob"), o.optBoolean("actual")});
        }
        out.sort((x, y) -> ((String) x[0]).compareTo((String) y[0]));
        return out;
    }

    /** What planj would have said on each past evening for one outcome, and what happened: {date, prob, actual}. */
    static List<Object[]> replay(History hist, String outcomeId) {
        List<Object[]> out = new ArrayList<>();
        if (outcomeId.equals(CLASS)) { // over every class day TAR UMT marked, not only the days planj saw
            for (TarcAttendance.ClassDay c : hist.classes) {
                Forecast fc = classForecast(hist, c.date.minusDays(1));
                if (fc != null) out.add(new Object[]{c.date, fc.prob, c.made});
            }
            return out;
        }
        for (LocalDate evening : hist.keySet()) {
            LocalDate target = evening.plusDays(1);
            if (!hist.containsKey(target)) continue;
            for (Forecast fc : forecast(hist, evening)) {
                if (!fc.outcome.id.equals(outcomeId)) continue;
                Boolean y = resolve(hist, target, outcomeId);
                if (y != null) out.add(new Object[]{target, fc.prob, y});
            }
        }
        return out;
    }

    static synchronized Map<String, Record> settle(Context ctx, History hist) {
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
