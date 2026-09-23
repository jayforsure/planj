package com.planj.phone;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

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

    void refresh() {
        new Thread(() -> {
            List<OddsEngine.Odd> odds = OddsEngine.compute(a);
            a.runOnUiThread(() -> render(odds));
        }).start();
    }

    private void render(List<OddsEngine.Odd> odds) {
        list.removeAllViews();
        if (odds.isEmpty()) {
            summary.setText("Odds need a few days of history. Keep the phone tracking and check back.");
            return;
        }
        summary.setText("How likely, going by your own last " + odds.get(0).days + " days. Tap the bar in Today to see the days behind a number.");
        String section = null;
        for (OddsEngine.Odd o : odds) {
            if (!o.section.equals(section)) {
                section = o.section;
                TextView h = new TextView(a);
                h.setText(section);
                h.setTextColor(a.getColor(R.color.muted));
                h.setTextSize(11);
                h.setLetterSpacing(0.12f);
                h.setPadding(0, dp(18), 0, dp(6));
                list.addView(h);
            }
            list.addView(card(o));
        }
    }

    private View card(OddsEngine.Odd o) {
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
        q.setText(o.question);
        q.setTextColor(a.getColor(R.color.text));
        q.setTextSize(16);
        q.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        left.addView(q);
        TextView ev = new TextView(a);
        ev.setText(o.evidence);
        ev.setTextColor(a.getColor(R.color.muted));
        ev.setTextSize(12);
        ev.setPadding(0, dp(3), 0, dp(8));
        left.addView(ev);

        int color = o.pkg != null ? AppPalette.color(o.pkg) : a.getColor(R.color.accent);
        FrameLayout track = new FrameLayout(a);
        track.setBackgroundResource(R.drawable.field_bg);
        View fill = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(4));
        g.setColor(color);
        fill.setBackground(g);
        track.addView(fill, new FrameLayout.LayoutParams(0, dp(5)));
        left.addView(track, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)));
        // Size the fill once the track has a width; post() can run before the first layout.
        track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = Math.max(dp(4), Math.round((r - l) * o.percent() / 100f));
            if (fill.getLayoutParams().width != width) {
                fill.getLayoutParams().width = width;
                fill.requestLayout();
            }
        });
        card.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView pct = new TextView(a);
        pct.setText(o.percent() + "%");
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
