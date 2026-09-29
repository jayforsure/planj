package com.planj.phone;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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

    void refresh() {
        new Thread(() -> {
            Result r = new Result();
            r.hist = OddsEngine.history(a);
            LocalDate today = LocalDate.now();
            r.forecasts = OddsEngine.forecast(r.hist, today);
            if (!r.forecasts.isEmpty()) OddsEngine.record(a, today, r.forecasts);
            r.live = OddsEngine.settle(a, r.hist);
            r.back = OddsEngine.backtest(r.hist);
            r.tomorrow = Agenda.on(a, today.plusDays(1));
            a.runOnUiThread(() -> render(r));
        }).start();
    }

    private void render(Result r) {
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
                line((e.allDay ? "All day" : Fmt.clock(e.startMs)) + "  ·  " + e.title, a.getColor(R.color.text), 15);
            }
        } else if (!Agenda.allowed(a)) {
            header("TOMORROW'S PLANS");
            line("Calendar access is off", a.getColor(R.color.muted), 13);
        }

        List<OddsEngine.Forecast> routines = new java.util.ArrayList<>(), plain = new java.util.ArrayList<>();
        for (OddsEngine.Forecast fc : r.forecasts) (fc.outcome.id.startsWith("rt_") ? routines : plain).add(fc);
        if (!routines.isEmpty()) {
            header("YOUR ROUTINES");
            for (OddsEngine.Forecast fc : routines) list.addView(card(fc));
        }
        header("FORECASTS");
        for (OddsEngine.Forecast fc : plain) list.addView(card(fc));

        header("TRACK RECORD");
        LinearLayout table = new LinearLayout(a);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setBackgroundResource(R.drawable.card_bg);
        table.setPadding(dp(18), dp(12), dp(18), dp(12));
        table.addView(row("", "Live", "Replay", true));
        boolean anyLive = false;
        for (OddsEngine.Forecast fc : r.forecasts) {
            OddsEngine.Record lv = r.live.get(fc.outcome.id), bk = r.back.get(fc.outcome.id);
            String live = lv != null && lv.n > 0 ? lv.hits + "/" + lv.n : "–";
            if (lv != null && lv.n > 0) anyLive = true;
            String replay = bk != null && bk.n > 0 ? bk.hits + "/" + bk.n + "  ·  avg " + bk.baseHits + "/" + bk.n : "–";
            Routines.Routine rt = Routines.Routine.parse(fc.outcome.id);
            String label = rt == null ? fc.outcome.resolved
                    : RoutineNames.name(a, rt).toLowerCase(Locale.ENGLISH) + " " + rt.window();
            table.addView(row(label, live, replay, false));
        }
        list.addView(table);

    }

    private View row(String name, String live, String replay, boolean head) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(head ? 2 : 7), 0, dp(head ? 6 : 7));
        int muted = a.getColor(R.color.muted), text = a.getColor(R.color.text);
        TextView n = cell(name, head ? muted : text, head ? 12 : 14);
        TextView l = cell(live, muted, head ? 12 : 14);
        TextView p = cell(replay, muted, head ? 12 : 14);
        if (head) {
            for (TextView t : new TextView[]{n, l, p}) t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        }
        r.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f));
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f));
        r.addView(p, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
        return r;
    }

    private TextView cell(String s, int color, int sp) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** A section heading in planj's voice: the display face with the teal dot. */
    private boolean first = true;

    private void header(String text) {
        TextView h = new TextView(a, null, 0, R.style.Heading_Dot);
        h.setText(text.charAt(0) + text.substring(1).toLowerCase(java.util.Locale.ENGLISH));
        h.setTextAppearance(R.style.Heading);
        h.setTypeface(a.getResources().getFont(R.font.display));
        h.setFontVariationSettings("'wght' 700, 'opsz' 40, 'wdth' 100");
        h.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.dot_accent, 0, 0, 0);
        h.setCompoundDrawablePadding(dp(10));
        h.setGravity(android.view.Gravity.CENTER_VERTICAL);
        h.setPadding(0, dp(first ? 28 : 32), 0, dp(12));
        first = false;
        list.addView(h);
    }

    private void line(String text, int color, int sp) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(4), 0, dp(4));
        list.addView(t);
    }

    private View card(OddsEngine.Forecast fc) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.bottomMargin = dp(8);
        card.setLayoutParams(cp);

        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView q = new TextView(a);
        Routines.Routine rt = Routines.Routine.parse(fc.outcome.id);
        q.setText(rt == null ? fc.outcome.question : RoutineNames.title(a, rt));
        if (rt != null) {
            card.setBackgroundResource(R.drawable.card_clickable);
            card.setOnClickListener(v -> askName(rt));
        }
        q.setTextColor(a.getColor(R.color.text));
        q.setTextSize(16);
        q.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        left.addView(q);
        TextView ev = new TextView(a);
        String evidence = "usually " + Math.round(fc.base * 100) + "% of " + fc.n + " days";
        if (fc.leverText != null) evidence += " · " + fc.sideK + " of " + fc.sideN + " " + fc.leverText;
        ev.setText(evidence);
        ev.setTextColor(a.getColor(R.color.muted));
        ev.setTextSize(12);
        ev.setPadding(0, dp(3), 0, dp(8));
        left.addView(ev);

        FrameLayout track = new FrameLayout(a);
        track.setBackgroundResource(R.drawable.field_bg);
        View fill = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(4));
        g.setColor(a.getColor(R.color.accent));
        fill.setBackground(g);
        track.addView(fill, new FrameLayout.LayoutParams(0, dp(5)));
        left.addView(track, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)));
        int percent = (int) Math.round(fc.prob * 100);
        track.addOnLayoutChangeListener((v, l, t, rr, b, ol, ot, or, ob) -> {
            int width = Math.max(dp(4), Math.round((rr - l) * percent / 100f));
            if (fill.getLayoutParams().width != width) {
                fill.getLayoutParams().width = width;
                fill.requestLayout();
            }
        });
        card.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView pct = new TextView(a);
        pct.setText(percent + "%");
        pct.setTextColor(a.getColor(R.color.text));
        pct.setTextSize(30);
        pct.setTypeface(a.getResources().getFont(R.font.display));
        pct.setFontVariationSettings("'wght' 800, 'opsz' 96, 'wdth' 100");
        pct.setLetterSpacing(-0.03f);
        pct.setPadding(dp(16), 0, 0, 0);
        card.addView(pct);
        return card;
    }

    /** One question, asked only if the person wants to: what is this routine? */
    private void askName(Routines.Routine rt) {
        android.widget.EditText in = new android.widget.EditText(a);
        String current = RoutineNames.name(a, rt);
        boolean unnamed = current.equals(rt.kind.defaultName) || current.equals(Places.labelFor(a, rt.place));
        in.setText(unnamed ? "" : current);
        in.setHint("Gym, class, work…");
        in.setSingleLine(true);
        int pad = dp(20);
        in.setPadding(pad, pad, pad, pad);
        new android.app.AlertDialog.Builder(a)
                .setTitle(rt.days() + " " + rt.window())
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    RoutineNames.set(a, rt, in.getText().toString());
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
