package com.planj.phone;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Routines the person never had to declare: blocks of the day that repeat on the same
 * weekdays at about the same time. Two kinds so far:
 *   FREE - the phone goes untouched (whether or not it came along)
 *   AWAY - the phone is off the home Wi-Fi
 * Pure Java on purpose, so it can be checked without a phone.
 */
final class Routines {
    enum Kind {
        FREE(9 * 60, 22 * 60 + 30, "Phone down"),
        AWAY(6 * 60, 23 * 60, "Out"),
        AT(6 * 60, 23 * 60, "Place");       // at one particular place; the routine carries which

        final int from, to; // the part of the day this kind is looked for in, minutes
        final String defaultName;

        Kind(int from, int to, String defaultName) {
            this.from = from;
            this.to = to;
            this.defaultName = defaultName;
        }
    }

    static final int SLOT = 15;         // minutes
    static final int MIN_LEN = 45;      // a routine block lasts at least this long
    static final int MAX_ROUTINES = 4;
    static final int MIN_EVIDENCE = 5;  // days looked at behind any routine: 3 of 4 Fridays is chance, not habit

    /** One day's blocks, in minutes since local midnight. A null list means "not measurable". */
    interface DayBlocks {
        LocalDate date();

        List<int[]> blocks(Kind kind);

        /** Time at one place (not home). Null when places are off or unknown that day. */
        default List<int[]> placeBlocks(String place) {
            return null;
        }

        /** The places seen that day, other than home. */
        default java.util.Set<String> places() {
            return java.util.Collections.emptySet();
        }
    }

    static final class Routine {
        final Kind kind;
        final String place;      // for AT: which place, e.g. "p3"; null otherwise
        final int mask;          // bit 0 = Monday ... bit 6 = Sunday
        final int start, end;    // minutes since midnight
        final double freq;       // how often it showed up when it was looked for
        int evidence;            // how many days it was looked for on

        Routine(Kind kind, int mask, int start, int end, double freq) {
            this(kind, null, mask, start, end, freq);
        }

        Routine(Kind kind, String place, int mask, int start, int end, double freq) {
            this.kind = kind;
            this.place = place;
            this.mask = mask;
            this.start = start;
            this.end = end;
            this.freq = freq;
        }

        /** Stable across refits, so the forecast ledger can settle it later. */
        String id() {
            String k = kind == Kind.AT ? "at-" + place : kind.name().toLowerCase(Locale.ROOT);
            return "rt_" + k + "_" + mask + "_" + start + "_" + end;
        }

        static Routine parse(String id) {
            String[] p = id.split("_");
            if (p.length != 5 || !p[0].equals("rt")) return null;
            try {
                int mask = Integer.parseInt(p[2]), start = Integer.parseInt(p[3]), end = Integer.parseInt(p[4]);
                if (p[1].startsWith("at-")) return new Routine(Kind.AT, p[1].substring(3), mask, start, end, 0);
                return new Routine(Kind.valueOf(p[1].toUpperCase(Locale.ROOT)), mask, start, end, 0);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        List<int[]> blocksOf(DayBlocks day) {
            return kind == Kind.AT ? day.placeBlocks(place) : day.blocks(kind);
        }

        boolean appliesTo(LocalDate d) {
            return (mask & (1 << (d.getDayOfWeek().getValue() - 1))) != 0;
        }

        /** Did the block happen that day: covering at least half the routine's window. */
        Boolean happened(DayBlocks day) {
            if (!appliesTo(day.date())) return null;
            List<int[]> blocks = blocksOf(day);
            if (blocks == null) return null;
            int covered = 0;
            for (int[] b : blocks) covered += Math.max(0, Math.min(b[1], end) - Math.max(b[0], start));
            return covered * 2 >= end - start;
        }

        String window() {
            return clock(start) + "–" + clock(end);
        }

        String days() {
            if (mask == 0b0011111) return "Weekdays";
            if (mask == 0b1100000) return "Weekends";
            if (mask == 0b1111111) return "Daily";
            List<String> names = new ArrayList<>();
            for (DayOfWeek d : DayOfWeek.values()) {
                if ((mask & (1 << (d.getValue() - 1))) != 0) {
                    names.add(d.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH));
                }
            }
            return String.join(", ", names);
        }
    }

    private Routines() {}

    static String clock(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** Finds the routines in the given days. Needs about three weeks for per-weekday ones. */
    static List<Routine> find(List<? extends DayBlocks> days) {
        List<Routine> all = new ArrayList<>();
        java.util.Set<String> places = new java.util.TreeSet<>();
        for (DayBlocks d : days) places.addAll(d.places());
        List<String> sources = new ArrayList<>();
        sources.add("FREE");
        sources.add("AWAY");
        for (String p : places) sources.add("AT:" + p);
        List<Routine> atRoutines = new ArrayList<>();
        for (String src : sources) {
            Kind kind = src.startsWith("AT:") ? Kind.AT : Kind.valueOf(src);
            String place = kind == Kind.AT ? src.substring(3) : null;
            List<Routine> grouped = new ArrayList<>();
            grouped.addAll(runs(days, kind, place, 0b0011111, 5, 0.6));  // weekdays
            grouped.addAll(runs(days, kind, place, 0b1100000, 4, 0.66)); // weekends
            List<Routine> single = new ArrayList<>();
            for (int d = 0; d < 7; d++) {
                for (Routine r : runs(days, kind, place, 1 << d, 3, 0.66)) {
                    if (!coveredBy(r, grouped)) single.add(r);
                }
            }
            List<Routine> found = new ArrayList<>(grouped);
            found.addAll(mergeWeekdays(single));
            found.removeIf(r -> r.evidence < MIN_EVIDENCE);
            if (kind == Kind.AT) atRoutines.addAll(found);
            all.addAll(found);
        }
        // "At the gym" says more than "out": drop an out-routine that a place-routine explains.
        all.removeIf(r -> r.kind == Kind.AWAY && atRoutines.stream().anyMatch(a -> (a.mask & r.mask) != 0 && overlapsMostly(a, r)));
        all.sort((a, b) -> Double.compare(b.freq * (b.end - b.start), a.freq * (a.end - a.start)));
        return new ArrayList<>(all.subList(0, Math.min(MAX_ROUTINES, all.size())));
    }

    /** Stretches of the day that a block covered on at least `threshold` of the matching days. */
    private static List<Routine> runs(List<? extends DayBlocks> days, Kind kind, String place, int mask, int minDays, double threshold) {
        int slots = (kind.to - kind.from) / SLOT;
        int[] hits = new int[slots];
        int n = 0;
        for (DayBlocks d : days) {
            if ((mask & (1 << (d.date().getDayOfWeek().getValue() - 1))) == 0) continue;
            List<int[]> blocks = kind == Kind.AT ? d.placeBlocks(place) : d.blocks(kind);
            if (blocks == null) continue;
            n++;
            for (int s = 0; s < slots; s++) {
                int lo = kind.from + s * SLOT, hi = lo + SLOT;
                for (int[] b : blocks) {
                    if (b[0] <= lo && b[1] >= hi) {
                        hits[s]++;
                        break;
                    }
                }
            }
        }
        List<Routine> out = new ArrayList<>();
        if (n < minDays) return out;
        int s = 0;
        while (s < slots) {
            if ((double) hits[s] / n < threshold) {
                s++;
                continue;
            }
            int e = s;
            double sum = 0;
            while (e < slots && (double) hits[e] / n >= threshold) sum += (double) hits[e++] / n;
            if ((e - s) * SLOT >= MIN_LEN) {
                Routine r = new Routine(kind, place, mask, kind.from + s * SLOT, kind.from + e * SLOT, sum / (e - s));
                r.evidence = n;
                out.add(r);
            }
            s = e;
        }
        return out;
    }

    private static boolean overlapsMostly(Routine a, Routine b) {
        int ov = Math.min(a.end, b.end) - Math.max(a.start, b.start);
        return ov * 2 >= Math.min(a.end - a.start, b.end - b.start);
    }

    private static boolean coveredBy(Routine r, List<Routine> grouped) {
        for (Routine g : grouped) if ((g.mask & r.mask) != 0 && overlapsMostly(r, g)) return true;
        return false;
    }

    /** Tuesday 19:00–20:30 and Thursday 19:15–20:30 are one routine on two days. */
    private static List<Routine> mergeWeekdays(List<Routine> single) {
        List<Routine> left = new ArrayList<>(single);
        List<Routine> out = new ArrayList<>();
        while (!left.isEmpty()) {
            Routine base = left.remove(0);
            int mask = base.mask, count = 1, evidence = base.evidence;
            long start = base.start, end = base.end;
            double freq = base.freq;
            for (int i = left.size() - 1; i >= 0; i--) {
                Routine r = left.get(i);
                if (java.util.Objects.equals(base.place, r.place) && overlapsMostly(base, r)) {
                    mask |= r.mask;
                    start += r.start;
                    end += r.end;
                    freq += r.freq;
                    evidence += r.evidence;
                    count++;
                    left.remove(i);
                }
            }
            int s = (int) Math.round((double) start / count / SLOT) * SLOT;
            int e = (int) Math.round((double) end / count / SLOT) * SLOT;
            Routine merged = new Routine(base.kind, base.place, mask, s, e, freq / count);
            merged.evidence = evidence;
            out.add(merged);
        }
        return out;
    }

    /** Helper for tests and the engine: sorted, merged blocks at least MIN_LEN long within a kind's hours. */
    static List<int[]> clean(List<int[]> raw, Kind kind) {
        List<int[]> in = new ArrayList<>();
        for (int[] b : raw) {
            int s = Math.max(b[0], kind.from), e = Math.min(b[1], kind.to);
            if (e > s) in.add(new int[]{s, e});
        }
        in.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] b : in) {
            if (!merged.isEmpty() && b[0] <= merged.get(merged.size() - 1)[1]) {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], b[1]);
            } else {
                merged.add(b);
            }
        }
        List<int[]> out = new ArrayList<>();
        for (int[] b : merged) if (b[1] - b[0] >= MIN_LEN) out.add(b);
        return Collections.unmodifiableList(out);
    }

    /** The gaps between uses, i.e. the complement of `used` within the kind's hours. */
    static List<int[]> gaps(List<int[]> used, Kind kind) {
        List<int[]> u = new ArrayList<>(used);
        u.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> out = new ArrayList<>();
        int cursor = kind.from;
        for (int[] b : u) {
            if (b[1] <= cursor) continue;
            if (b[0] > cursor) out.add(new int[]{cursor, Math.min(b[0], kind.to)});
            cursor = Math.max(cursor, b[1]);
            if (cursor >= kind.to) break;
        }
        if (cursor < kind.to) out.add(new int[]{cursor, kind.to});
        return clean(out, kind);
    }
}
