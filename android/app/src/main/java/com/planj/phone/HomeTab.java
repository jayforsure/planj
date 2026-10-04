package com.planj.phone;

import android.app.Dialog;
import android.content.Intent;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Home: tomorrow, for your goals. The goal tonight can change most as a ring, the move that
 * changes it, quick things to do, your other goals to swipe through, and planj's record as dots.
 * Each goal opens its own page.
 */
final class HomeTab {
    private final MainActivity a;
    private final View root;
    private final LinearLayout body;
    private final TextView date, initial;
    private final ImageView photo;

    HomeTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_home, container, false);
        container.addView(root);
        body = root.findViewById(R.id.home_body);
        date = root.findViewById(R.id.home_date);
        initial = root.findViewById(R.id.home_initial);
        photo = root.findViewById(R.id.home_photo);
        root.findViewById(R.id.home_avatar).setOnClickListener(v -> a.showAccount());
        header();
    }

    View view() {
        return root;
    }

    private void header() {
        date.setText(LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)));
        Avatar.show(a, photo, initial);
    }

    void show(OddsTab.Result r) {
        header();
        body.removeAllViews();
        if (!Connectors.usageAccess(a)) {
            LinearLayout c = card();
            c.addView(heading("planj can't see your phone yet"));
            c.addView(words("It learns your days from Android's usage access: which apps, when the screen is on, when you put it down.", R.color.muted, 14, 8));
            Button b = new Button(a, null, 0, R.style.Pill_Primary);
            b.setText("Allow usage access");
            b.setOnClickListener(v -> a.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            lp.topMargin = dp(18);
            c.addView(b, lp);
            actions();
            return;
        }
        List<Goals.Goal> withOdds = new ArrayList<>();
        for (Goals.Goal g : r.goals) if (r.forecast(g) != null) withOdds.add(g);
        if (withOdds.isEmpty()) {
            if (r.goals.isEmpty()) pickGoals();
            else learning(Math.min(r.hist.size(), OddsEngine.MIN_HISTORY));
            actions();
            if (!r.goals.isEmpty()) goals(r, r.goals);
            return;
        }
        Goals.Goal lead = lead(r, withOdds);
        hero(r, lead);
        tonight(r, lead);
        actions();
        List<Goals.Goal> rest = new ArrayList<>(r.goals);
        rest.remove(lead);
        if (!rest.isEmpty()) goals(r, rest);
        record();
    }

    /** The goal tonight can change most; without any moves yet, the one furthest from usual. */
    private static Goals.Goal lead(OddsTab.Result r, List<Goals.Goal> withOdds) {
        Goals.Goal best = null;
        double bestScore = -1;
        for (Goals.Goal g : withOdds) {
            OddsEngine.Move m = r.moves.get(g.id);
            OddsEngine.Forecast fc = r.forecast(g);
            double score = m != null ? 1 + m.with() - m.without() : Math.abs(g.chance(fc) - g.usual(fc));
            if (score > bestScore) {
                bestScore = score;
                best = g;
            }
        }
        return best;
    }

    /** The headline: your chance of one goal, said so the words and the number agree. */
    private void hero(OddsTab.Result r, Goals.Goal g) {
        OddsEngine.Forecast fc = r.forecast(g);
        double chance = g.chance(fc);
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        OddsRing ring = new OddsRing(a);
        ring.setPercent((int) Math.round(chance * 100));
        c.addView(ring, new LinearLayout.LayoutParams(dp(168), dp(168)));
        TextView s = words(OddsWords.goalSentence(a, g, chance), R.color.text, 19, 16);
        s.setGravity(Gravity.CENTER);
        s.setTypeface(a.getResources().getFont(R.font.display));
        s.setFontVariationSettings("'wght' 700, 'opsz' 40, 'wdth' 100");
        c.addView(s);
        TextView why = words(OddsWords.goalWhy(g, fc), R.color.muted, 13, 12);
        why.setBackgroundResource(R.drawable.chip_soft);
        why.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = dp(12);
        c.addView(why, wl);
        c.setOnClickListener(v -> OddsDetailActivity.open(a, g.outcomeId));
    }

    /**
     * Tonight: the one thing to do, and what it has meant for your goals on nights like it. The
     * lead goal's move first; every goal it helps is listed under it.
     */
    private void tonight(OddsTab.Result r, Goals.Goal lead) {
        String moveId = null;
        Goals.Goal opens = lead;
        if (r.moves.containsKey(lead.id)) {
            moveId = r.moves.get(lead.id).id;
        } else {
            double best = 0;
            for (Goals.Goal g : r.goals) {
                OddsEngine.Move m = r.moves.get(g.id);
                if (m != null && m.with() - m.without() > best) {
                    best = m.with() - m.without();
                    moveId = m.id;
                    opens = g;
                }
            }
        }
        LinearLayout c = card();
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView moon = new ImageView(a);
        moon.setImageResource(R.drawable.ic_moon);
        moon.setBackgroundResource(R.drawable.icon_circle);
        moon.setPadding(dp(13), dp(13), dp(13), dp(13));
        moon.setImageTintList(android.content.res.ColorStateList.valueOf(a.getColor(R.color.accent)));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(48), dp(48));
        ml.setMarginEnd(dp(14));
        top.addView(moon, ml);
        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(words("Tonight", R.color.muted, 13, 0));
        TextView what = words(moveId == null ? "Learning what your nights change" : OddsWords.move(moveId), R.color.text, 18, 2);
        what.setTypeface(a.getResources().getFont(R.font.display));
        what.setFontVariationSettings("'wght' 700, 'opsz' 40, 'wdth' 100");
        text.addView(what);
        top.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(top);
        if (moveId == null) {
            c.addView(words("Once planj has seen 4 nights each way, it shows the one thing tonight that changes tomorrow most.", R.color.muted, 13, 12));
            c.setClickable(false);
            return;
        }
        List<Goals.Goal> helped = new ArrayList<>(r.goals);
        helped.remove(opens);
        helped.add(0, opens); // the goal it was picked for first
        for (Goals.Goal g : helped) {
            OddsEngine.Move m = r.moves.get(g.id);
            if (m != null && m.id.equals(moveId)) c.addView(words(OddsWords.moveEffect(g, m), R.color.text, 14, 10));
        }
        c.addView(words("From your own nights: a pattern, not a promise.", R.color.muted, 12, 10));
        Goals.Goal target = opens;
        c.setOnClickListener(v -> OddsDetailActivity.open(a, target.outcomeId));
    }

    private void pickGoals() {
        LinearLayout c = card();
        c.addView(heading("What do you want more of?"));
        c.addView(words("Pick a goal or two, and planj gives you the odds of each for tomorrow, and what tonight changes.", R.color.muted, 14, 8));
        Button b = new Button(a, null, 0, R.style.Pill_Primary);
        b.setText("Pick your goals");
        b.setOnClickListener(v -> a.startActivity(new Intent(a, GoalsActivity.class)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(18);
        c.addView(b, lp);
    }

    /** Things to do, Wise-style: a row of buttons under the headline. */
    private void actions() {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        body.addView(row, lp);
        boolean paused = PrivateMode.isOn(a);
        action(row, R.drawable.ic_mood, "Check in", this::checkIn);
        action(row, R.drawable.ic_today, "Your day", a::showDays);
        action(row, R.drawable.ic_private, paused ? "Resume" : "Pause", () -> a.setPrivate(!paused));
        action(row, R.drawable.ic_add, "Connect", () -> a.startActivity(new Intent(a, ConnectorsActivity.class)));
    }

    private void action(LinearLayout row, int icon, String label, Runnable onClick) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackgroundResource(R.drawable.btn_text);
        col.setPadding(0, dp(8), 0, dp(8));
        col.setOnClickListener(v -> onClick.run());
        ImageView b = new ImageView(a);
        b.setImageResource(icon);
        b.setBackgroundResource(R.drawable.btn_circle);
        b.setPadding(dp(15), dp(15), dp(15), dp(15));
        b.setImageTintList(android.content.res.ColorStateList.valueOf(a.getColor(R.color.text)));
        col.addView(b, new LinearLayout.LayoutParams(dp(54), dp(54)));
        TextView t = words(label, R.color.text, 13, 8);
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    /** How was today: the five moods in a sheet, saved with one tap. */
    private void checkIn() {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, dp(4));
        LocalDate day = MoodStore.today();
        Dialog[] sheet = new Dialog[1];
        MoodPicker picker = new MoodPicker(a, row, mood -> {
            MoodStore.Entry e = MoodStore.entryFor(a, day);
            a.saveEntry(day, mood, e == null ? "" : e.note, e == null ? List.of() : e.tags);
            if (sheet[0] != null) sheet[0].dismiss();
            a.toast("Saved. Add a note any time in Days");
        });
        MoodStore.Entry now = MoodStore.entryFor(a, day);
        picker.select(now == null ? 0 : now.mood);
        sheet[0] = Sheet.custom(a, "How was today?", "One tap. planj learns how your days feel next to how they went.", row);
    }

    /** Your other goals, as cards to swipe through. */
    private void goals(OddsTab.Result r, List<Goals.Goal> list) {
        LinearLayout title = new LinearLayout(a);
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = new TextView(a, null, 0, R.style.Heading_Dot);
        h.setText("Your goals");
        title.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView all = new TextView(a, null, 0, R.style.SectionLink);
        all.setText("See all");
        all.setOnClickListener(v -> a.showAllOdds());
        title.addView(all);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(20);
        tl.bottomMargin = dp(12);
        body.addView(title, tl);

        SnapScroller scroller = new SnapScroller(a);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroller.addView(row);
        for (Goals.Goal g : list) {
            OddsEngine.Forecast fc = r.forecast(g);
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setBackgroundResource(R.drawable.card_clickable);
            c.setPadding(dp(16), dp(16), dp(16), dp(16));
            OddsRing ring = new OddsRing(a);
            ring.setPercent(fc == null ? -1 : (int) Math.round(g.chance(fc) * 100));
            c.addView(ring, new LinearLayout.LayoutParams(dp(72), dp(72)));
            TextView t = words(g.name, R.color.text, 15, 12);
            t.setMaxLines(2);
            c.addView(t);
            c.addView(words(fc == null ? OddsTab.waiting(a, r, g) : OddsWords.verdictLine(g.chance(fc)), R.color.muted, 12, 4));
            c.setOnClickListener(v -> {
                if (fc != null) OddsDetailActivity.open(a, g.outcomeId);
                else a.showAllOdds();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(156), ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMarginEnd(dp(10));
            row.addView(c, lp);
        }
        body.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** planj's record: one dot per forecast already checked, filled when it was right. */
    private void record() {
        List<Object[]> checked = OddsEngine.checked(a, null);
        List<Boolean> dots = new ArrayList<>();
        int hits = 0;
        for (Object[] c : checked.subList(Math.max(0, checked.size() - 60), checked.size())) {
            boolean right = ((Double) c[2] >= 0.5) == (Boolean) c[3];
            dots.add(right);
            if (right) hits++;
        }
        LinearLayout title = new LinearLayout(a);
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = new TextView(a, null, 0, R.style.Heading_Dot);
        h.setText("planj's record");
        title.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView aside = new TextView(a, null, 0, R.style.SectionAside);
        aside.setText(dots.isEmpty() ? "" : "Right " + hits + " of " + dots.size());
        title.addView(aside);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(24);
        tl.bottomMargin = dp(12);
        body.addView(title, tl);
        LinearLayout c = card();
        if (dots.isEmpty()) {
            c.addView(words("Your first forecasts are checked tomorrow morning, against what really happened.", R.color.muted, 14, 0));
        } else {
            DotStrip strip = new DotStrip(a);
            strip.setDots(dots);
            c.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            c.addView(words("Each dot is a forecast, checked the next morning. Filled means planj was right.", R.color.muted, 13, 12));
        }
        c.setOnClickListener(v -> a.showAllOdds());
    }

    private void learning(int days) {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        OddsRing ring = new OddsRing(a);
        ring.setPercent(Math.round(100f * Math.max(1, days) / OddsEngine.MIN_HISTORY));
        c.addView(ring, new LinearLayout.LayoutParams(dp(140), dp(140)));
        TextView t = words("Getting to know you · day " + Math.max(1, days) + " of " + OddsEngine.MIN_HISTORY, R.color.text, 18, 16);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(a.getResources().getFont(R.font.display));
        c.addView(t);
        TextView b = words("In a few days planj starts telling you tomorrow's odds, and checks every one the next morning.", R.color.muted, 14, 8);
        b.setGravity(Gravity.CENTER);
        c.addView(b);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card_clickable);
        c.setPadding(dp(20), dp(22), dp(20), dp(22));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        body.addView(c, lp);
        return c;
    }

    private TextView heading(String s) {
        TextView t = new TextView(a, null, 0, R.style.Heading);
        t.setText(s);
        return t;
    }

    private TextView words(String s, int color, int sp, int topDp) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(a.getColor(color));
        t.setTextSize(sp);
        t.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
