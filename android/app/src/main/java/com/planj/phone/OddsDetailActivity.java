package com.planj.phone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** One forecast: the odds, why, what moves them, how often planj has been right, and what checks it. */
public class OddsDetailActivity extends Activity {
    private static final String EXTRA = "outcome";
    private PageBuilder page;
    private String id;

    static void open(Context ctx, String outcomeId) {
        ctx.startActivity(new Intent(ctx, OddsDetailActivity.class).putExtra(EXTRA, outcomeId));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_page);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        page = new PageBuilder(this, findViewById(R.id.stage));
        id = getIntent().getStringExtra(EXTRA);
        load();
    }

    private void load() {
        new Thread(() -> {
            TreeMap<LocalDate, OddsEngine.Day> hist = OddsEngine.history(this);
            OddsEngine.Forecast fc = null;
            for (OddsEngine.Forecast f : OddsEngine.forecast(hist, LocalDate.now())) if (f.outcome.id.equals(id)) fc = f;
            List<Object[]> replay = OddsEngine.replay(hist, id);
            List<Object[]> live = OddsEngine.checked(this, id);
            OddsEngine.Forecast found = fc;
            runOnUiThread(() -> {
                if (!isFinishing()) render(found, replay, live);
            });
        }).start();
    }

    private void render(OddsEngine.Forecast fc, List<Object[]> replay, List<Object[]> live) {
        page.clear();
        String question = fc != null ? fc.outcome.question : id;
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        page.stage.addView(head);
        OddsRing ring = new OddsRing(this);
        ring.setPercent(fc == null ? -1 : (int) Math.round(fc.prob * 100));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(page.dp(184), page.dp(184));
        rl.topMargin = page.dp(4);
        head.addView(ring, rl);
        TextView s = new TextView(this);
        s.setText(OddsWords.sentence(this, id, question));
        s.setTextColor(getColor(R.color.text));
        s.setTextSize(22);
        s.setGravity(Gravity.CENTER);
        s.setTypeface(getResources().getFont(R.font.display));
        s.setFontVariationSettings("'wght' 700, 'opsz' 40, 'wdth' 100");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = page.dp(18);
        head.addView(s, sl);
        TextView why = new TextView(this);
        why.setText(fc == null ? "Not forecast for tomorrow yet" : OddsWords.why(fc));
        why.setTextColor(getColor(R.color.muted));
        why.setTextSize(13);
        why.setBackgroundResource(R.drawable.chip_soft);
        why.setPadding(page.dp(12), page.dp(7), page.dp(12), page.dp(7));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = page.dp(12);
        head.addView(why, wl);

        if (fc != null) {
            page.section("What moves it");
            if (fc.leverId != null && fc.otherN > 0) {
                int here = (int) Math.round(100.0 * (fc.sideK + 1) / (fc.sideN + 2));
                int there = (int) Math.round(100.0 * (fc.otherK + 1) / (fc.otherN + 2));
                bar(OddsWords.side(fc.leverId, fc.leverSide), "today", here, fc.sideK, fc.sideN);
                bar(OddsWords.side(fc.leverId, !fc.leverSide), null, there, fc.otherK, fc.otherN);
            } else {
                page.note("Nothing has moved it yet: it has happened on " + Math.round(fc.base * 100) + "% of your days. "
                        + "planj looks for what changes it as your days add up.");
            }
        }

        List<Boolean> liveDots = dots(live, 2, 3), replayDots = dots(replay, 1, 2);
        page.section("planj's record");
        if (!liveDots.isEmpty()) {
            dotCard(liveDots, "Checked each morning since planj started forecasting this");
        }
        if (!replayDots.isEmpty()) {
            dotCard(replayDots, "Replayed over your past days: what planj would have said, and what happened");
        }
        if (liveDots.isEmpty() && replayDots.isEmpty()) page.note("No forecasts checked yet. The first is checked tomorrow morning.");

        page.section("How it's checked");
        Connectors.Connector c = Connectors.get(ConnectorOdds.owner(id));
        ListRow r = page.link(c.glyph, c.name(this), c.checkedAgainst, () -> c.open(this));
        Connectors.fillRow(this, r, c, c.checkedAgainst);

        Routines.Routine rt = Routines.Routine.parse(id);
        if (rt != null) {
            page.link(R.drawable.ic_edit, "Name this routine", RoutineNames.name(this, rt), () ->
                    Sheet.input(this, R.drawable.ic_edit, "What is this?", rt.days() + " " + rt.window(), RoutineNames.name(this, rt),
                            "Gym, class, work…", 30, name -> {
                                RoutineNames.set(this, rt, name);
                                load();
                            }));
        }
    }

    /** One side of what moves it: a label, its odds, and a bar. */
    private void bar(String label, String tag, int percent, int k, int n) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, page.dp(8), 0, page.dp(10));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(getColor(tag == null ? R.color.muted : R.color.text));
        l.setTextSize(15);
        top.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = new TextView(this);
        v.setText(percent + "%");
        v.setTextColor(getColor(tag == null ? R.color.muted : R.color.text));
        v.setTextSize(17);
        v.setTypeface(getResources().getFont(R.font.display));
        top.addView(v);
        box.addView(top);
        FrameLayout track = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable t = new android.graphics.drawable.GradientDrawable();
        t.setColor(getColor(R.color.surface_alt));
        t.setCornerRadius(page.dp(4));
        track.setBackground(t);
        View fill = new View(this);
        android.graphics.drawable.GradientDrawable f = new android.graphics.drawable.GradientDrawable();
        f.setColor(getColor(tag == null ? R.color.idle : R.color.accent));
        f.setCornerRadius(page.dp(4));
        fill.setBackground(f);
        track.addView(fill, new FrameLayout.LayoutParams(0, page.dp(8)));
        track.addOnLayoutChangeListener((vv, a, b, c, d, e, g, h, i) -> {
            int w = Math.max(page.dp(8), Math.round((c - a) * percent / 100f));
            if (fill.getLayoutParams().width != w) {
                fill.getLayoutParams().width = w;
                fill.requestLayout();
            }
        });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, page.dp(8));
        tl.topMargin = page.dp(8);
        box.addView(track, tl);
        TextView from = new TextView(this);
        from.setText(k + " of " + n + " days like this" + (tag == null ? "" : " · like today"));
        from.setTextColor(getColor(R.color.muted));
        from.setTextSize(12);
        from.setPadding(0, page.dp(6), 0, 0);
        box.addView(from);
        page.stage.addView(box);
    }

    private void dotCard(List<Boolean> dots, String caption) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card_bg);
        c.setPadding(page.dp(18), page.dp(18), page.dp(18), page.dp(18));
        int hits = 0;
        for (boolean b : dots) if (b) hits++;
        TextView t = new TextView(this);
        t.setText("Right " + hits + " of " + dots.size());
        t.setTextColor(getColor(R.color.text));
        t.setTextSize(16);
        c.addView(t);
        DotStrip strip = new DotStrip(this);
        strip.setDots(dots);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = page.dp(12);
        c.addView(strip, sl);
        TextView cap = new TextView(this);
        cap.setText(caption);
        cap.setTextColor(getColor(R.color.muted));
        cap.setTextSize(12);
        cap.setPadding(0, page.dp(10), 0, 0);
        c.addView(cap);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = page.dp(8);
        page.stage.addView(c, lp);
    }

    /** Right or wrong for each row, given where its probability and its outcome sit. */
    private static List<Boolean> dots(List<Object[]> rows, int probAt, int actualAt) {
        List<Boolean> out = new ArrayList<>();
        for (Object[] r : rows) out.add(((Double) r[probAt] >= 0.5) == (Boolean) r[actualAt]);
        return out.subList(Math.max(0, out.size() - 60), out.size());
    }
}
