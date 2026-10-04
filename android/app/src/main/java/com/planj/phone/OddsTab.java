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
        OddsEngine.History hist;
        List<OddsEngine.Forecast> forecasts;
        Map<String, OddsEngine.Record> live;
        List<Goals.Goal> goals;
        Map<String, OddsEngine.Move> moves = new java.util.HashMap<>(); // by goal id, only those with one
        Map<String, List<Boolean>> lately = new java.util.HashMap<>();   // by goal id: reached on each of the last days
        String tonight;                                                  // tonight's move, or null while learning
        List<Goals.Goal> helped = new java.util.ArrayList<>();           // goals it helps, the one it was picked for first
        List<Boolean> tonightWeek = new java.util.ArrayList<>();         // did it on each of the last 7 nights
        String lastMove;                                                 // last night's move, how it went, and that night
        Boolean lastDid;
        OddsEngine.Day lastDay;

        OddsEngine.Forecast forecast(Goals.Goal g) {
            for (OddsEngine.Forecast f : forecasts) if (f.outcome.id.equals(g.outcomeId)) return f;
            return null;
        }
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
            r.goals = Goals.chosen(a);
            for (Goals.Goal g : r.goals) {
                OddsEngine.Move m = OddsEngine.move(r.hist, g.outcomeId, g.good);
                if (m != null) r.moves.put(g.id, m);
                r.lately.put(g.id, OddsEngine.lastDays(r.hist, g.outcomeId, g.good, 7));
            }
            pickTonight(r);
            LocalDate evening = MoveReminder.evening();
            if (r.tonight != null) MoveReminder.shown(a, evening, r.tonight);
            r.lastMove = MoveReminder.shownOn(a, evening.minusDays(1));
            if (r.lastMove != null) {
                r.lastDay = r.hist.get(evening); // the night before a day belongs to it
                r.lastDid = OddsEngine.did(r.hist, r.lastMove, evening);
            }
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

    /** Tonight's move: the one that lifts a goal most, and every goal it helps. */
    private static void pickTonight(Result r) {
        double best = 0;
        Goals.Goal pickedFor = null;
        for (Goals.Goal g : r.goals) {
            OddsEngine.Move m = r.moves.get(g.id);
            if (m != null && m.with() - m.without() > best) {
                best = m.with() - m.without();
                r.tonight = m.id;
                pickedFor = g;
            }
        }
        if (pickedFor == null) return;
        r.helped.add(pickedFor);
        for (Goals.Goal g : r.goals) {
            OddsEngine.Move m = r.moves.get(g.id);
            if (g != pickedFor && m != null && m.id.equals(r.tonight)) r.helped.add(g);
        }
        LocalDate today = LocalDate.now();
        for (LocalDate d = today.minusDays(6); !d.isAfter(today); d = d.plusDays(1)) {
            Boolean did = OddsEngine.did(r.hist, r.tonight, d);
            if (did != null) r.tonightWeek.add(did);
        }
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
        summary.setText("How the last 7 days went, and planj's guess for tomorrow");
        first = false; // the page title already says it; no section heading over the goals
        for (Goals.Goal g : r.goals) list.addView(goalRow(a, r, g, true));
        ListRow change = plainRow(R.drawable.ic_target, r.goals.isEmpty() ? "Pick your goals" : "Change your goals", "Pick what you want more of");
        change.setChevron(true);
        change.setClickable(true);
        change.setBackgroundResource(R.drawable.btn_text);
        change.setOnClickListener(v -> a.startActivity(new android.content.Intent(a, GoalsActivity.class)));
        list.addView(change);

        // how often planj's guesses came true: is it worth listening to?
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
            TextView cap = new TextView(a);
            cap.setText("Each dot is one of planj's guesses, checked the next day. Filled means it came true.");
            cap.setTextColor(a.getColor(R.color.muted));
            cap.setTextSize(13);
            cap.setPadding(0, dp(10), 0, 0);
            list.addView(cap);
        }
    }

    /**
     * One goal as a row: how often you reached it lately, as words and dots, and (in the full
     * list) planj's guess for tomorrow in a word. Opens the goal's page.
     */
    static View goalRow(android.app.Activity a, Result r, Goals.Goal g, boolean withGuess) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, Math.round(10 * d), 0, Math.round(10 * d));
        row.setBackgroundResource(R.drawable.btn_text);
        row.setOnClickListener(v -> OddsDetailActivity.open(a, g.outcomeId));
        android.widget.ImageView icon = new android.widget.ImageView(a);
        icon.setImageResource(g.icon);
        icon.setBackgroundResource(R.drawable.icon_circle);
        int pad = Math.round(14 * d);
        icon.setPadding(pad, pad, pad, pad);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(a.getColor(R.color.text)));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Math.round(52 * d), Math.round(52 * d));
        il.setMarginEnd(Math.round(16 * d));
        row.addView(icon, il);
        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(a);
        name.setText(g.name);
        name.setTextColor(a.getColor(R.color.text));
        name.setTextSize(16);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        text.addView(name);
        List<Boolean> days = r.lately.get(g.id);
        boolean known = days != null && !days.isEmpty();
        OddsEngine.Forecast fc = r.forecast(g);
        boolean between = g.id.equals("class") && fc == null; // last semester's misses aren't news between semesters
        String sub = known && !between ? OddsWords.lately(g, days) : waiting(a, r, g);
        if (withGuess && fc != null) sub += " · tomorrow: " + OddsWords.guess(g.chance(fc)).toLowerCase(Locale.ENGLISH);
        TextView s = new TextView(a);
        s.setText(sub);
        s.setTextColor(a.getColor(R.color.muted));
        s.setTextSize(13);
        text.addView(s);
        if (known) {
            DotStrip strip = new DotStrip(a);
            strip.setDots(days);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.topMargin = Math.round(8 * d);
            text.addView(strip, sl);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    /** Why a goal has no odds for tomorrow yet. */
    static String waiting(android.content.Context ctx, Result r, Goals.Goal g) {
        if (g.id.equals("class")) {
            int days = OddsEngine.daysFor(r.hist, LocalDate.now(), OddsEngine.CLASS);
            if (days < OddsEngine.MIN_HISTORY) return "Learning · " + days + " of " + OddsEngine.MIN_HISTORY + " class days";
            return Boolean.TRUE.equals(TarcTimetable.inSemester(ctx, LocalDate.now().plusDays(1))) ? "No class tomorrow" : "Back when classes start";
        }
        int days = OddsEngine.daysFor(r.hist, LocalDate.now(), g.outcomeId);
        return days < OddsEngine.MIN_HISTORY ? "Learning · " + days + " of " + OddsEngine.MIN_HISTORY + " days" : "The same every day so far";
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
            case OddsEngine.CLASS: return R.drawable.ic_school;
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

    private int dp(int v) {
        return Math.round(v * density);
    }
}
