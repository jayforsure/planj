package com.planj.phone;

/** A plain-Java check of PcFocus, run with the JDK outside Android. */
public class PcFocusCheck {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        PcFocus d = new PcFocus();
        d.add(9 * 60, 11 * 60 + 30, "focus");        // 2h30 of work
        d.add(22 * 60 + 30, 23 * 60 + 40, "entertainment"); // 1h10 watching, 40 min of it late
        d.add(12 * 60, 12 * 60 + 20, "other");
        check(Math.abs(d.focusH - 2.5) < 1e-9, "focus hours add up");
        check(Math.abs(d.watchH - 70 / 60.0) < 1e-9, "watching hours add up");
        check(d.lateWatchMin == 40, "only watching from 11pm counts as late");
        check(d.sorted(), "a day with real categories is sorted");

        PcFocus old = new PcFocus();
        old.add(9 * 60, 12 * 60, "other"); // recorded before the PC sorted anything
        check(PcFocus.count(old, false, true) == null, "days before sorting began are unknown, not zero focus");
        check(PcFocus.count(old, true, true) == old, "an all-other day after sorting began is a real day");
        check(PcFocus.count(null, false, true) == null, "no report before sorting began: unknown");
        PcFocus off = PcFocus.count(null, true, true);
        check(off != null && off.focusH == 0, "no report once sorting began, after the day settles: the PC stayed off");
        check(PcFocus.count(null, true, false) == null, "no report for yesterday yet: wait, it may still come");
        check(PcFocus.count(d, false, false) == d, "a sorted report always counts");

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
