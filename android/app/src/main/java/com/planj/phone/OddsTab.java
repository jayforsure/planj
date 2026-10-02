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

    static final class Result {
        TreeMap<LocalDate, OddsEngine.Day> hist;
        List<OddsEngine.Forecast> forecasts;
        Map<String, OddsEngine.Record> live;
    }

    /** Also told whenever fresh odds are worked out: the home page shows the headline ones. */
    java.util.function.Consumer<Result> onResult;

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
            a.runOnUiThread(() -> {
                render(r);
                if (onResult != null) onResult.accept(r);
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
        if (fromSkeleton) { // the real rows settle in where the grey ones were
            list.setAlpha(0f);
            list.animate().alpha(1f).setDuration(220).start();
        }
        list.removeAllViews();
        first = true;
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        summary.setText("Tomorrow · " + tomorrow.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH)));
        if (r.forecasts.isEmpty()) {
            header("LEARNING");
            ListRow row = plainRow(R.drawable.ic_odds, "Getting to know you", r.hist.size() + " of " + OddsEngine.MIN_HISTORY + " days so far");
            list.addView(row);
            return;
        }

        // planj's record first, as dots: is it worth listening to?
        List<Object[]> checked = OddsEngine.checked(a, null);
        if (!checked.isEmpty()) {
            List<Boolean> dots = new java.util.ArrayList<>();
            int hits = 0;
            for (Object[] c : checked.subList(Math.max(0, checked.size() - 60), checked.size())) {
                boolean right = ((Double) c[2] >= 0.5) == (Boolean) c[3];
                dots.add(right);
                if (right) hits++;
            }
            header("PLANJ'S RECORD", "Right " + hits + " of " + dots.size());
            DotStrip strip = new DotStrip(a);
            strip.setDots(dots);
            list.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        header("TOMORROW", r.forecasts.size() + " odds");
        for (OddsEngine.Forecast fc : HomeTab.top(r.forecasts, r.forecasts.size())) list.addView(card(fc));
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (OddsEngine.Forecast fc : r.forecasts) shown.add(fc.outcome.id);
        for (OddsEngine.Outcome o : OddsEngine.OUTCOMES) { // questions still being learned, quietly at the end
            if (shown.contains(o.id)) continue;
            int days = OddsEngine.daysFor(r.hist, LocalDate.now(), o.id);
            if (days >= OddsEngine.MIN_HISTORY) continue; // the same every day so far: nothing to say
            ListRow row = plainRow(iconFor(o.id), OddsWords.title(a, o.id, o.question), "Learning · " + days + " of " + OddsEngine.MIN_HISTORY + " days");
            row.setValue("–", true);
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

    /** One odds as a quiet row: its title, more or less likely than usual, and the number. Opens its page. */
    private View card(OddsEngine.Forecast fc) {
        String trend = Math.abs(fc.prob - fc.base) < 0.03 ? "As usual" : fc.prob < fc.base ? "Less likely than usual" : "More likely than usual";
        ListRow row = plainRow(iconFor(fc.outcome.id), OddsWords.title(a, fc.outcome.id, fc.outcome.question), trend);
        row.setBigValue(Math.round(fc.prob * 100) + "%", false);
        row.setClickable(true);
        row.setBackgroundResource(R.drawable.btn_text);
        row.setOnClickListener(v -> OddsDetailActivity.open(a, fc.outcome.id));
        return row;
    }


    private int dp(int v) {
        return Math.round(v * density);
    }
}
