package com.planj.phone;

import android.content.Context;
import android.widget.LinearLayout;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a connector adds to your odds: the forecasts it answers (with today's odds, or how far
 * along planj is in learning them) and the signals it gives other forecasts.
 */
final class ConnectorOdds {
    /** Which connector each signal comes from, and its plain name. */
    private static final Map<String, String[]> SIGNALS = new LinkedHashMap<>();

    static {
        SIGNALS.put("short_night", new String[]{"phone", "short nights"});
        SIGNALS.put("late_phone", new String[]{"phone", "phone past midnight"});
        SIGNALS.put("social_heavy", new String[]{"phone", "heavy social days"});
        SIGNALS.put("many_unlocks", new String[]{"phone", "many unlocks"});
        SIGNALS.put("charged", new String[]{"phone", "charging overnight"});
        SIGNALS.put("pc_focus_day", new String[]{"pc", "focused PC days"});
        SIGNALS.put("pc_watch_late", new String[]{"pc", "late watching"});
        SIGNALS.put("pc_watch_heavy", new String[]{"pc", "long watching"});
        SIGNALS.put("out_at_place", new String[]{"places", "days out"});
        SIGNALS.put("early_plans", new String[]{"calendar", "early plans"});
        SIGNALS.put("class_morning", new String[]{"tarc", "morning classes"});
    }

    static final class Row {
        final int icon;
        final String title, subtitle, value;
        final boolean learning;

        Row(int icon, String title, String subtitle, String value, boolean learning) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.value = value;
            this.learning = learning;
        }
    }

    private ConnectorOdds() {}

    /** The connector whose data answers a forecast. */
    static String owner(String outcomeId) {
        if (outcomeId.equals(OddsEngine.FOCUS)) return "pc";
        if (outcomeId.equals(OddsEngine.CLASS)) return "tarc";
        Routines.Routine r = Routines.Routine.parse(outcomeId);
        if (r != null) return r.kind == Routines.Kind.AT ? "places" : "phone";
        return "phone";
    }

    /** "5 forecasts · 5 signals", without needing any history. */
    static String summary(String id) {
        int forecasts = 0, signals = 0;
        for (OddsEngine.Outcome o : OddsEngine.OUTCOMES) if (owner(o.id).equals(id)) forecasts++;
        if (id.equals("tarc")) forecasts += 2; // making your first class, finishing what's due
        for (String[] s : SIGNALS.values()) if (s[0].equals(id)) signals++;
        List<String> parts = new ArrayList<>();
        if (forecasts > 0) parts.add(forecasts + (forecasts == 1 ? " forecast" : " forecasts"));
        if (id.equals("phone") || id.equals("places")) parts.add("routines");
        if (signals > 0) parts.add(signals + (signals == 1 ? " signal" : " signals"));
        return "Adds " + String.join(" · ", parts);
    }

    /** Works it out; call off the main thread. */
    static List<Row> rows(Context ctx, String id) {
        OddsEngine.History hist = OddsEngine.history(ctx);
        LocalDate today = LocalDate.now();
        List<OddsEngine.Forecast> forecasts = OddsEngine.forecast(hist, today);
        List<Row> out = new ArrayList<>();

        for (OddsEngine.Outcome o : OddsEngine.OUTCOMES) {
            if (!owner(o.id).equals(id)) continue;
            OddsEngine.Forecast fc = find(forecasts, o.id);
            if (fc != null) {
                out.add(new Row(OddsTab.iconFor(o.id), o.question, "Usually " + Math.round(fc.base * 100) + "%"
                        + (fc.leverText == null ? "" : " · " + fc.leverText), Math.round(fc.prob * 100) + "%", false));
            } else {
                int days = OddsEngine.daysFor(hist, today, o.id);
                out.add(new Row(OddsTab.iconFor(o.id), o.question, days < OddsEngine.MIN_HISTORY
                        ? "Learning · " + days + " of " + OddsEngine.MIN_HISTORY + " days"
                        : "The same every day so far", "–", true));
            }
        }

        if (id.equals("phone") || id.equals("places")) {
            boolean any = false;
            for (OddsEngine.Forecast fc : forecasts) {
                Routines.Routine r = Routines.Routine.parse(fc.outcome.id);
                if (r == null || !owner(fc.outcome.id).equals(id)) continue;
                any = true;
                out.add(new Row(OddsTab.iconFor(fc.outcome.id), RoutineNames.title(ctx, r),
                        "Usually " + Math.round(fc.base * 100) + "%", Math.round(fc.prob * 100) + "%", false));
            }
            if (!any) out.add(new Row(id.equals("places") ? R.drawable.ic_place : R.drawable.ic_phone, "Routines",
                    "Learning · they appear once a pattern repeats 5 times", "–", true));
        }

        if (id.equals("tarc")) {
            OddsEngine.Forecast fc = find(forecasts, OddsEngine.CLASS);
            int days = OddsEngine.daysFor(hist, today, OddsEngine.CLASS);
            if (fc != null) {
                out.add(new Row(R.drawable.ic_school, OddsWords.title(ctx, OddsEngine.CLASS, fc.outcome.question),
                        "Tomorrow · usually " + Math.round(fc.base * 100) + "%", Math.round(fc.prob * 100) + "%", false));
            } else {
                out.add(new Row(R.drawable.ic_school, "Make your first class", days < OddsEngine.MIN_HISTORY
                        ? "Learning · " + days + " of " + OddsEngine.MIN_HISTORY + " class days"
                        : "Forecast the evening before each class day", "–", true));
            }
            String odds = TarcDeadlines.odds(ctx);
            int seen = TarcDeadlines.settled(ctx);
            List<TarcDeadlines.Pending> pending = TarcDeadlines.pending(ctx);
            String next = pending.isEmpty() ? "" : " · next: " + pending.get(0).title;
            out.add(new Row(R.drawable.ic_bell, "Finish what's due in time", odds == null
                    ? "Learning · " + seen + " of " + TarcDeadlines.MIN_SEEN + " tasks seen" + next
                    : "From " + seen + " tasks" + next, odds == null ? "–" : odds, odds == null));
        }

        List<String> names = new ArrayList<>(), used = new ArrayList<>();
        for (Map.Entry<String, String[]> e : SIGNALS.entrySet()) {
            if (!e.getValue()[0].equals(id)) continue;
            names.add(e.getValue()[1]);
            for (OddsEngine.Forecast fc : forecasts) {
                OddsEngine.Lever lv = lever(e.getKey());
                if (lv != null && fc.leverText != null && (fc.leverText.equals(lv.whenTrue) || fc.leverText.equals(lv.whenFalse))) {
                    used.add(fc.outcome.question.toLowerCase(Locale.ENGLISH));
                }
            }
        }
        if (!names.isEmpty()) {
            String list = String.join(", ", names);
            String sub = used.isEmpty()
                    ? Character.toUpperCase(list.charAt(0)) + list.substring(1) + " · used when they sharpen a forecast"
                    : "Shaping " + String.join(", ", new java.util.LinkedHashSet<>(used)) + " today · from " + list;
            out.add(new Row(R.drawable.ic_sync, "Signals", sub, "", used.isEmpty()));
        }
        return out;
    }

    /** Fills a section with the rows, as soon as they are worked out. */
    static void fillAsync(android.app.Activity a, LinearLayout into, String id, String checkedAgainst) {
        PageBuilder page = new PageBuilder(a, into);
        page.section("Adds to your odds");
        page.note("Working it out…");
        new Thread(() -> {
            List<Row> rows;
            try {
                rows = rows(a, id);
            } catch (Exception e) {
                rows = new ArrayList<>();
            }
            List<Row> done = rows;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                into.removeAllViews();
                page.section("Adds to your odds");
                for (Row r : done) {
                    ListRow row = page.row(r.icon, r.title, r.subtitle);
                    if (!r.value.isEmpty()) row.setValue(r.value, r.learning);
                }
                page.row(R.drawable.ic_shield, "Checked against", checkedAgainst);
            });
        }).start();
    }

    private static OddsEngine.Forecast find(List<OddsEngine.Forecast> fcs, String id) {
        for (OddsEngine.Forecast f : fcs) if (f.outcome.id.equals(id)) return f;
        return null;
    }

    private static OddsEngine.Lever lever(String id) {
        for (OddsEngine.Lever l : OddsEngine.LEVERS) if (l.id.equals(id)) return l;
        return null;
    }
}
