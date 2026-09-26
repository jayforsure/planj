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
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        if (r.forecasts.isEmpty()) {
            summary.setText("Forecasts need " + OddsEngine.MIN_HISTORY + " days of history — " + r.hist.size() + " so far. Keep the phone tracking.");
            return;
        }
        summary.setText("Tomorrow, " + tomorrow.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH))
                + " · from your last " + r.hist.size() + " days · recorded now, scored in the morning.");

        if (!r.tomorrow.isEmpty()) {
            header("TOMORROW'S PLANS");
            for (Agenda.Event e : r.tomorrow) {
                line((e.allDay ? "All day" : Fmt.clock(e.startMs)) + "  ·  " + e.title, a.getColor(R.color.text), 15);
            }
        } else if (!Agenda.allowed(a)) {
            header("TOMORROW'S PLANS");
            line("Allow calendar access in Settings and tomorrow's plans appear here.", a.getColor(R.color.muted), 13);
        }

        header("FORECASTS");
        for (OddsEngine.Forecast fc : r.forecasts) list.addView(card(fc));

        header("TRACK RECORD");
        boolean anyLive = false;
        for (OddsEngine.Outcome oc : OddsEngine.OUTCOMES) {
            OddsEngine.Record lv = r.live.get(oc.id), bk = r.back.get(oc.id);
            StringBuilder s = new StringBuilder(oc.resolved).append(":  ");
            if (lv != null && lv.n > 0) {
                s.append(lv.hits).append(" of ").append(lv.n).append(" right");
                anyLive = true;
            } else {
                s.append("no scored forecasts yet");
            }
            if (bk != null && bk.n > 0) {
                s.append("\n   replaying your history: ").append(bk.hits).append("/").append(bk.n)
                        .append(" right, vs ").append(bk.baseHits).append("/").append(bk.n).append(" guessing your average");
            }
            line(s.toString(), a.getColor(R.color.muted), 13);
        }
        line(anyLive ? "Live scores count forecasts made in the app; the replay shows what the same rules would have scored on earlier days."
                : "Tonight's forecasts are the first. The replay line shows what these rules would have scored on your earlier days.",
                a.getColor(R.color.idle), 12);
    }

    private void header(String text) {
        TextView h = new TextView(a);
        h.setText(text);
        h.setTextColor(a.getColor(R.color.muted));
        h.setTextSize(11);
        h.setLetterSpacing(0.12f);
        h.setPadding(0, dp(18), 0, dp(6));
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
        q.setText(fc.outcome.question);
        q.setTextColor(a.getColor(R.color.text));
        q.setTextSize(16);
        q.setTypeface(Typeface.create("serif", Typeface.NORMAL));
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
        pct.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        pct.setPadding(dp(16), 0, 0, 0);
        card.addView(pct);
        return card;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
