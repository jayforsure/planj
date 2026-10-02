package com.planj.phone;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class OddsTab {
    private final MainActivity a;
    private final View root;
    private final TextView summary;
    private final LinearLayout list;
    private final float density;

    OddsTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_odds, container, false);
        container.addView(root);
        summary = root.findViewById(R.id.odds_summary);
        list = root.findViewById(R.id.odds_list);
        density = a.getResources().getDisplayMetrics().density;
    }

    View view() {
        return root;
    }

    private static final class Result {
        TreeMap<LocalDate, OddsEngine.Day> hist;
        List<OddsEngine.Forecast> forecasts;
        Map<String, OddsEngine.Record> live, back;
        List<Agenda.Event> tomorrow;
    }

    private boolean running, again; // main thread only

    /** One calculation at a time; a request that arrives meanwhile runs once after it. */
    void refresh() {
        if (running) {
            again = true;
            return;
        }
        running = true;
        if (list.getChildCount() == 0) showSkeleton(); // first time: the shape of what's coming
        new Thread(() -> {
            Result r = new Result();
            r.hist = OddsEngine.history(a);
            LocalDate today = LocalDate.now();
            r.forecasts = OddsEngine.forecast(r.hist, today);
            if (!r.forecasts.isEmpty()) OddsEngine.record(a, today, r.forecasts);
            r.live = OddsEngine.settle(a, r.hist);
            r.back = OddsEngine.backtest(r.hist);
            r.tomorrow = new java.util.ArrayList<>(Agenda.on(a, today.plusDays(1)));
            r.tomorrow.addAll(TarcTimetable.asPlans(a, today.plusDays(1))); // classes from TAR UMT
            r.tomorrow.addAll(TarcDue.asPlans(a, today.plusDays(1)));       // and anything due
            r.tomorrow.sort((x, y) -> Long.compare(x.startMs, y.startMs));
            a.runOnUiThread(() -> {
                render(r);
                running = false;
                if (again) {
                    again = false;
                    refresh();
                }
            });
        }).start();
    }

    private android.animation.ObjectAnimator pulse;

    /** Grey rows shaped like forecasts, breathing gently, while the numbers are worked out. */
    private void showSkeleton() {
        summary.setText(" ");
        LinearLayout ghosts = new LinearLayout(a);
        ghosts.setOrientation(LinearLayout.VERTICAL);
        ghosts.setPadding(0, dp(72), 0, 0);
        for (int i = 0; i < 5; i++) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            View square = new View(a);
            square.setBackgroundResource(R.drawable.icon_circle);
            LinearLayout.LayoutParams sq = new LinearLayout.LayoutParams(dp(52), dp(52));
            sq.setMarginEnd(dp(16));
            row.addView(square, sq);
            LinearLayout text = new LinearLayout(a);
            text.setOrientation(LinearLayout.VERTICAL);
            text.addView(bar(i % 2 == 0 ? 0.62f : 0.5f, 14));
            text.addView(bar(0.4f, 10));
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ghosts.addView(row);
        }
        list.addView(ghosts);
        pulse = android.animation.ObjectAnimator.ofFloat(ghosts, View.ALPHA, 1f, 0.45f);
        pulse.setDuration(700);
        pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        pulse.start();
    }

    private View bar(float share, int heightDp) {
        View v = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setColor(a.getColor(R.color.surface_alt));
        g.setCornerRadius(dp(4));
        v.setBackground(g);
        int w = Math.round((a.getResources().getDisplayMetrics().widthPixels - dp(108)) * share);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, dp(heightDp));
        lp.topMargin = heightDp == 14 ? 0 : dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private void render(Result r) {
        boolean fromSkeleton = pulse != null;
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (fromSkeleton) { // the real cards settle in where the grey ones were
            list.setAlpha(0f);
            list.animate().alpha(1f).setDuration(220).start();
        }
        list.removeAllViews();
        first = true;
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        if (r.forecasts.isEmpty()) {
            summary.setText(r.hist.size() + " of " + OddsEngine.MIN_HISTORY + " days recorded");
            return;
        }
        summary.setText("Tomorrow · " + tomorrow.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH)));

        if (!r.tomorrow.isEmpty()) {
            header("TOMORROW'S PLANS");
            for (Agenda.Event e : r.tomorrow) {
                list.addView(plainRow(R.drawable.ic_journal, e.title, e.allDay ? "All day" : Fmt.clock(e.startMs)));
            }
        } else if (!Agenda.allowed(a)) {
            header("TOMORROW'S PLANS");
            ListRow cal = plainRow(R.drawable.ic_journal, "Calendar", "Not connected · connect it to see tomorrow's plans");
            cal.setChevron(true);
            cal.setClickable(true);
            cal.setBackgroundResource(R.drawable.btn_text);
            cal.setOnClickListener(v -> a.startActivity(new android.content.Intent(a, ConnectorActivity.class)
                    .putExtra(ConnectorActivity.EXTRA_ID, "calendar")));
            list.addView(cal);
        }

        List<OddsEngine.Forecast> routines = new java.util.ArrayList<>(), plain = new java.util.ArrayList<>();
        for (OddsEngine.Forecast fc : r.forecasts) (fc.outcome.id.startsWith("rt_") ? routines : plain).add(fc);
        if (!routines.isEmpty()) {
            header("YOUR ROUTINES");
            for (OddsEngine.Forecast fc : routines) list.addView(card(fc));
        }
        header("FORECASTS");
        for (OddsEngine.Forecast fc : plain) list.addView(card(fc));
        boolean focusShown = false;
        for (OddsEngine.Forecast fc : plain) focusShown |= fc.outcome.id.equals(OddsEngine.FOCUS);
        if (!focusShown) {
            int days = OddsEngine.daysFor(r.hist, LocalDate.now(), OddsEngine.FOCUS);
            list.addView(plainRow(R.drawable.ic_monitor, "2h+ focus on your PC", days < OddsEngine.MIN_HISTORY
                    ? "Gathering · " + days + " of " + OddsEngine.MIN_HISTORY + " days"
                    : "Not reached in " + days + " days yet"));
        }

        List<TarcDeadlines.Pending> due = TarcStore.connected(a) ? TarcDeadlines.pending(a) : new java.util.ArrayList<>();
        due.removeIf(d -> d.due.isAfter(LocalDate.now().plusDays(60)));
        if (!due.isEmpty()) { // TAR UMT's to-dos: will you finish each in time? checked by the dashboard itself
            String odds = TarcDeadlines.odds(a);
            int seen = TarcDeadlines.settled(a);
            header("DUE", "TAR UMT");
            for (TarcDeadlines.Pending d : due) {
                ListRow row = plainRow(R.drawable.ic_bell, d.title, "Due " + d.due.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
                        + " · " + (odds == null ? "learning, " + seen + " of " + TarcDeadlines.MIN_SEEN + " seen" : "odds you finish in time"));
                row.setSubtitleLines(2);
                if (odds != null) row.setBigValue(odds, false);
                else row.setValue(Upcoming.daysLeft(d.due), true);
                list.addView(row);
            }
        }

        header("TRACK RECORD", "live");
        for (OddsEngine.Forecast fc : r.forecasts) {
            OddsEngine.Record lv = r.live.get(fc.outcome.id), bk = r.back.get(fc.outcome.id);
            Routines.Routine rt = Routines.Routine.parse(fc.outcome.id);
            String label = rt == null ? fc.outcome.resolved
                    : RoutineNames.name(a, rt).toLowerCase(Locale.ENGLISH) + " " + rt.window();
            // right: how the forecasts made so far turned out; below: the same rule replayed over past days
            String replay = bk != null && bk.n > 0
                    ? "Replay " + bk.hits + "/" + bk.n + " · average " + bk.baseHits + "/" + bk.n : "No replay yet";
            ListRow row = plainRow(iconFor(fc.outcome.id), label.substring(0, 1).toUpperCase(Locale.ENGLISH) + label.substring(1), replay);
            row.setValue(lv != null && lv.n > 0 ? lv.hits + "/" + lv.n : "–", lv == null || lv.n == 0);
            list.addView(row);
        }
    }

    /** What each question is about, as the icon in its square. */
    static int iconFor(String id) {
        Routines.Routine rt = Routines.Routine.parse(id);
        if (rt != null) return rt.kind == Routines.Kind.FREE ? R.drawable.ic_phone : R.drawable.ic_place;
        switch (id) {
            case "off_by_1am":
            case "quiet_7h": return R.drawable.ic_moon;
            case "heavy_screen": return R.drawable.ic_phone;
            case "social_2h": return R.drawable.ic_people;
            case "up_by_8": return R.drawable.ic_sun;
            case OddsEngine.FOCUS: return R.drawable.ic_monitor;
            default: return R.drawable.ic_odds;
        }
    }

    /** An information row: same shape as every list row, but nothing to tap. */
    private ListRow plainRow(int icon, String title, String subtitle) {
        ListRow row = new ListRow(a);
        row.setIcon(icon);
        row.setTitle(title);
        row.setSubtitle(subtitle);
        row.setChevron(false);
        row.setClickable(false);
        row.setBackground(null);
        return row;
    }

    /** A section heading in planj's voice: the display face with the teal dot. */
    private boolean first = true;

    private void header(String text) {
        header(text, null);
    }

    /** A section heading, with an optional note on the right as on the other pages ("live"). */
    private void header(String text, String aside) {
        TextView h = new TextView(a, null, 0, R.style.Heading_Dot);
        h.setText(text.charAt(0) + text.substring(1).toLowerCase(java.util.Locale.ENGLISH));
        h.setTextAppearance(R.style.Heading);
        h.setTypeface(a.getResources().getFont(R.font.display));
        h.setFontVariationSettings("'wght' 700, 'opsz' 40, 'wdth' 100");
        h.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.dot_accent, 0, 0, 0);
        h.setCompoundDrawablePadding(dp(10));
        h.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(first ? 28 : 32), 0, dp(12));
        first = false;
        line.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (aside != null) {
            TextView t = new TextView(a);
            t.setText(aside);
            t.setTextColor(a.getColor(R.color.muted));
            t.setTextSize(14);
            line.addView(t);
        }
        list.addView(line);
    }

    private View card(OddsEngine.Forecast fc) {
        Routines.Routine rt = Routines.Routine.parse(fc.outcome.id);
        String evidence = "Usually " + Math.round(fc.base * 100) + "%";
        if (fc.leverText != null) evidence += " · " + fc.sideK + " of " + fc.sideN + " " + fc.leverText;
        ListRow row = plainRow(iconFor(fc.outcome.id), rt == null ? fc.outcome.question : RoutineNames.title(a, rt), evidence);
        row.setSubtitleLines(2);
        row.setBigValue(Math.round(fc.prob * 100) + "%", false);
        if (rt != null) { // a routine can be named; tapping asks what it is
            row.setBackgroundResource(R.drawable.btn_text);
            row.setClickable(true);
            row.setOnClickListener(v -> askName(rt));
        }
        return row;
    }

    /** One question, asked only if the person wants to: what is this routine? */
    private void askName(Routines.Routine rt) {
        String current = RoutineNames.name(a, rt);
        boolean unnamed = current.equals(rt.kind.defaultName) || current.equals(Places.labelFor(a, rt.place));
        Sheet.input(a, iconFor(rt.id()), "What is this?", rt.days() + " " + rt.window() + ". A name makes the forecast easier to read.",
                unnamed ? "" : current, "Gym, class, work…", 30, name -> {
                    RoutineNames.set(a, rt, name);
                    refresh();
                });
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
