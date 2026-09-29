package com.planj.phone;

import android.content.Context;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One day as three lanes: where you were, when the phone was on, and what the PC was used for. */
final class DayTimeline {
    static final class Where {
        final int start, end;
        final String place, label;
        final boolean home, marked;

        Where(int start, int end, String place, String label, boolean home, boolean marked) {
            this.start = start;
            this.end = end;
            this.place = place;
            this.label = label;
            this.home = home;
            this.marked = marked;
        }
    }

    final LocalDate day;
    final List<Where> where = new ArrayList<>();
    final List<int[]> phone = new ArrayList<>();
    List<PcDays.Seg> pc;   // null when the PC hasn't reported this day
    int now = 24 * 60;     // for today, the current minute; later lanes stop here

    private DayTimeline(LocalDate day) {
        this.day = day;
    }

    static DayTimeline build(Context ctx, LocalDate day) {
        DayTimeline t = new DayTimeline(day);
        ZoneId zone = ZoneId.systemDefault();
        long dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli();
        if (day.equals(LocalDate.now())) t.now = minute(System.currentTimeMillis(), dayStart);

        DayUsage u = DayUsage.load(ctx, day);
        for (long[] on : u.screenOn) {
            int s = minute(on[0], dayStart), e = minute(on[1], dayStart);
            if (e > s) t.phone.add(new int[]{s, e});
        }
        t.pc = PcDays.load(ctx, day);

        // Where: the place held at midnight (the last sample before it), then each change.
        String state = null;
        for (int back = 1; back <= 7 && state == null; back++) {
            List<String[]> earlier = DayUsage.load(ctx, day.minusDays(back)).place;
            if (!earlier.isEmpty()) state = earlier.get(earlier.size() - 1)[1];
        }
        String home = Places.homeId(ctx);
        List<Places.Place> known = Places.list(ctx);
        int since = 0;
        for (String[] ev : u.place) {
            int m = minute(Long.parseLong(ev[0]), dayStart);
            t.add(state, since, m, home, known);
            state = ev[1];
            since = m;
        }
        t.add(state, since, t.now, home, known);
        return t;
    }

    private void add(String place, int start, int end, String home, List<Places.Place> known) {
        if (place == null || end <= start) return;
        if (!where.isEmpty()) {
            Where last = where.get(where.size() - 1);
            if (last.place.equals(place) && last.end >= start) { // the same place again: one stretch
                where.set(where.size() - 1, new Where(last.start, end, place, last.label, last.home, last.marked));
                return;
            }
        }
        boolean marked = false;
        String label = Places.label(place);
        for (Places.Place p : known) {
            if (p.id.equals(place)) {
                label = p.title();
                marked = p.marked();
            }
        }
        where.add(new Where(start, end, place, label, place.equals(home), marked));
    }

    private static int minute(long ms, long dayStart) {
        return (int) Math.max(0, Math.min(24 * 60, (ms - dayStart) / 60000));
    }

    static String clock(int m) {
        m = Math.max(0, Math.min(24 * 60, m));
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60); // midnight at the end reads 24:00
    }

    /** "Home until 08:40 · School 09:10–13:00 · Home since 13:40" (stretches under 10 minutes skipped). */
    String whereLine() {
        List<String> parts = new ArrayList<>();
        List<Where> w = new ArrayList<>();
        for (Where x : where) if (x.end - x.start >= 10) w.add(x);
        for (int i = 0; i < w.size(); i++) {
            Where x = w.get(i);
            boolean first = i == 0 && x.start == 0, last = i == w.size() - 1 && x.end >= now;
            if (first && last) parts.add(x.label + (now < 24 * 60 ? " all day so far" : " all day"));
            else if (first) parts.add(x.label + " until " + clock(x.end));
            else if (last && now < 24 * 60) parts.add(x.label + " since " + clock(x.start));
            else parts.add(x.label + " " + clock(x.start) + "–" + clock(x.end));
        }
        return String.join(" · ", parts);
    }

    /** "PC · 2h 10m focus · 45m watching · 10m social" */
    String pcLine() {
        if (pc == null) return null;
        int focus = 0, fun = 0, social = 0, chat = 0;
        for (PcDays.Seg s : pc) {
            int m = s.end - s.start;
            switch (s.cat) {
                case "focus": focus += m; break;
                case "entertainment": fun += m; break;
                case "social": social += m; break;
                case "chat": chat += m; break;
                default: break;
            }
        }
        List<String> parts = new ArrayList<>();
        if (focus > 0) parts.add(dur(focus) + " focus");
        if (fun > 0) parts.add(dur(fun) + " watching");
        if (social > 0) parts.add(dur(social) + " social");
        if (chat > 0) parts.add(dur(chat) + " chat");
        return parts.isEmpty() ? "PC · no focus or watching recorded" : "PC · " + String.join(" · ", parts);
    }

    private static String dur(int minutes) {
        return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
    }
}
