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

/**
 * One goal: how often you reached it lately, tonight's move with what followed it on your own
 * days, planj's guess for tomorrow in words, how often those guesses came true, and what checks it.
 */
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
            OddsEngine.History hist = OddsEngine.history(this);
            OddsEngine.Forecast fc = null;
            for (OddsEngine.Forecast f : OddsEngine.forecast(hist, LocalDate.now())) if (f.outcome.id.equals(id)) fc = f;
            List<Object[]> replay = OddsEngine.replay(hist, id);
            List<Object[]> live = OddsEngine.checked(this, id);
            Goals.Goal g = Goals.forOutcome(id);
            OddsEngine.Move move = g == null ? null : OddsEngine.move(hist, id, g.good);
            List<Boolean> lately = g == null ? new ArrayList<>() : OddsEngine.lastDays(hist, id, g.good, 7);
            OddsTab.Result r = new OddsTab.Result();
            r.hist = hist;
            String waiting = g == null ? "" : OddsTab.waiting(this, r, g);
            OddsEngine.Forecast found = fc;
            runOnUiThread(() -> {
                if (!isFinishing() && g != null) render(found, g, move, lately, waiting, replay, live);
                else if (!isFinishing()) finish(); // every page is a goal's
            });
        }).start();
    }

    private void render(OddsEngine.Forecast fc, Goals.Goal g, OddsEngine.Move move, List<Boolean> lately, String waiting,
                        List<Object[]> replay, List<Object[]> live) {
        page.clear();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        page.stage.addView(head);
        int reached = 0;
        for (boolean b : lately) if (b) reached++;
        OddsRing ring = new OddsRing(this);
        if (lately.isEmpty()) ring.setPercent(-1);
        else ring.setCount(reached, lately.size());
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(page.dp(168), page.dp(168));
        rl.topMargin = page.dp(4);
        head.addView(ring, rl);
        TextView s = new TextView(this);
        s.setText(g.name);
        s.setTextColor(getColor(R.color.text));
        s.setTextSize(24);
        s.setGravity(Gravity.CENTER);
        s.setTypeface(getResources().getFont(R.font.display));
        s.setFontVariationSettings("'wght' 700, 'opsz' 40, 'wdth' 100");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = page.dp(16);
        head.addView(s, sl);
        TextView chip = new TextView(this);
        chip.setText(lately.isEmpty() ? g.about : (g.id.equals("class") ? OddsWords.lately(g, lately) : "Reached on " + OddsWords.lately(g, lately)));
        chip.setTextColor(getColor(R.color.muted));
        chip.setTextSize(13);
        chip.setBackgroundResource(R.drawable.chip_soft);
        chip.setPadding(page.dp(12), page.dp(7), page.dp(12), page.dp(7));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = page.dp(10);
        head.addView(chip, wl);

        page.section("Tonight's move");
        if (move != null) {
            bar(OddsWords.move(move.id), move.k + " of " + move.n, "Days you reached it, after nights like that", true, (float) move.k / move.n);
            bar("Other nights", move.otherK + " of " + move.otherN, "Days you reached it, after the rest", false, (float) move.otherK / move.otherN);
            page.note("From your own nights: a pattern, not a promise.");
        } else {
            page.note("Nothing yet. planj compares nights you did something with nights you didn't, "
                    + "and shows a move once it has seen 4 of each and it made a real difference.");
        }

        page.section("Tomorrow");
        if (fc != null) {
            page.row(R.drawable.ic_odds, OddsWords.guess(g.chance(fc)), OddsWords.guessWhy(g, fc));
        } else {
            page.row(R.drawable.ic_odds, "No guess yet", waiting);
        }

        List<Boolean> liveDots = dots(live, 2, 3), replayDots = dots(replay, 1, 2);
        boolean liveFirst = liveDots.size() >= 5 || replayDots.isEmpty();
        List<Boolean> shown = liveFirst ? liveDots : replayDots;
        if (!shown.isEmpty()) {
            page.section("planj's record");
            dotCard(shown, liveFirst ? "How often planj's guess for tomorrow came true"
                    : "planj's guesses replayed over your past days, and how often they came true");
        }

        page.section("How it's checked");
        Connectors.Connector c = Connectors.get(ConnectorOdds.owner(id));
        ListRow r = page.link(c.glyph, c.name(this), c.checkedAgainst, () -> c.open(this));
        Connectors.fillRow(this, r, c, c.checkedAgainst);
    }

    /** One side of a move: a label, a count ("8 of 12"), a bar filled that far, and what it counts. */
    private void bar(String label, String count, String caption, boolean today, float share) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, page.dp(8), 0, page.dp(10));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(getColor(today ? R.color.text : R.color.muted));
        l.setTextSize(15);
        top.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = new TextView(this);
        v.setText(count);
        v.setTextColor(getColor(today ? R.color.text : R.color.muted));
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
        f.setColor(getColor(today ? R.color.accent : R.color.idle));
        f.setCornerRadius(page.dp(4));
        fill.setBackground(f);
        track.addView(fill, new FrameLayout.LayoutParams(0, page.dp(8)));
        track.addOnLayoutChangeListener((vv, a, b, c, d, e, g, h, i) -> {
            int w = Math.max(page.dp(8), Math.round((c - a) * share));
            if (fill.getLayoutParams().width != w) {
                fill.getLayoutParams().width = w;
                fill.requestLayout();
            }
        });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, page.dp(8));
        tl.topMargin = page.dp(8);
        box.addView(track, tl);
        TextView from = new TextView(this);
        from.setText(caption);
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
