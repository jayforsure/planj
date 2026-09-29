package com.planj.phone;

/** One day on the PC as the forecasts see it: hours of focus and of watching, and late watching. */
final class PcFocus {
    static final double GOAL_H = 2;          // "2h+ focus on your PC"
    static final int LATE_FROM = 23 * 60;    // watching from 11pm on counts as late

    double focusH, watchH;
    int lateWatchMin;
    private boolean sorted; // at least one stretch had a real category, so the PC was sorting that day

    void add(int start, int end, String cat) {
        int m = Math.max(0, end - start);
        if ("focus".equals(cat)) focusH += m / 60.0;
        if ("entertainment".equals(cat)) {
            watchH += m / 60.0;
            lateWatchMin += Math.max(0, end - Math.max(start, LATE_FROM));
        }
        if (cat != null && !cat.isEmpty() && !"other".equals(cat)) sorted = true;
    }

    boolean sorted() {
        return sorted;
    }

    /**
     * What a day counts as. Before the PC first sorted focus from watching, a day is unknown,
     * because older records can't tell the two apart. After that, a day the PC sent is used as
     * sent, and a day it never sent means it stayed off: no focus, once the day is past the
     * point where a late report could still arrive.
     */
    static PcFocus count(PcFocus reported, boolean afterFirstSorted, boolean settled) {
        if (reported != null) return reported.sorted || afterFirstSorted ? reported : null;
        return afterFirstSorted && settled ? new PcFocus() : null;
    }
}
