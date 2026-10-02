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
 * Home: tomorrow. The most telling odds as a ring, quick things to do, the rest of tomorrow's
 * odds to swipe through, and planj's record as dots. Each odds opens its own page.
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
        if (r.forecasts.isEmpty()) {
            learning(Math.min(r.hist.size(), OddsEngine.MIN_HISTORY));
            actions();
            return;
        }
        List<OddsEngine.Forecast> ranked = top(r.forecasts, r.forecasts.size());
        hero(ranked.get(0));
        actions();
        if (ranked.size() > 1) more(ranked.subList(1, ranked.size()));
        record();
    }

    /** The headline: the one odds that says most about tomorrow. */
    private void hero(OddsEngine.Forecast fc) {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        OddsRing ring = new OddsRing(a);
        ring.setPercent((int) Math.round(fc.prob * 100));
        c.addView(ring, new LinearLayout.LayoutParams(dp(168), dp(168)));
        TextView s = words(OddsWords.sentence(a, fc.outcome.id, fc.outcome.question), R.color.text, 19, 16);
        s.setGravity(Gravity.CENTER);
        s.setTypeface(a.getResources().getFont(R.font.display));
        s.setFontVariationSettings("'wght' 700, 'opsz' 40, 'wdth' 100");
        c.addView(s);
        TextView why = words(OddsWords.why(fc), R.color.muted, 13, 12);
        why.setBackgroundResource(R.drawable.chip_soft);
        why.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = dp(12);
        c.addView(why, wl);
        c.setOnClickListener(v -> OddsDetailActivity.open(a, fc.outcome.id));
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

    /** Tomorrow's other odds, as cards to swipe through. */
    private void more(List<OddsEngine.Forecast> rest) {
        LinearLayout title = new LinearLayout(a);
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = new TextView(a, null, 0, R.style.Heading_Dot);
        h.setText("More for tomorrow");
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
        for (OddsEngine.Forecast fc : rest) {
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setBackgroundResource(R.drawable.card_clickable);
            c.setPadding(dp(16), dp(16), dp(16), dp(16));
            OddsRing ring = new OddsRing(a);
            ring.setPercent((int) Math.round(fc.prob * 100));
            c.addView(ring, new LinearLayout.LayoutParams(dp(72), dp(72)));
            TextView t = words(OddsWords.title(a, fc.outcome.id, fc.outcome.question), R.color.text, 15, 12);
            t.setMaxLines(2);
            c.addView(t);
            TextView w = words(Math.abs(fc.prob - fc.base) < 0.03 ? "As usual" : fc.prob < fc.base ? "Less likely than usual" : "More likely than usual",
                    R.color.muted, 12, 4);
            c.addView(w);
            c.setOnClickListener(v -> OddsDetailActivity.open(a, fc.outcome.id));
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

    /** The most telling first: those that differ most from your usual, then the surest. */
    static List<OddsEngine.Forecast> top(List<OddsEngine.Forecast> all, int k) {
        List<OddsEngine.Forecast> sorted = new ArrayList<>(all);
        sorted.sort((x, y) -> {
            double dx = Math.abs(x.prob - x.base), dy = Math.abs(y.prob - y.base);
            if (Math.abs(dx - dy) > 0.02) return Double.compare(dy, dx);
            return Double.compare(Math.abs(y.prob - 0.5), Math.abs(x.prob - 0.5));
        });
        return sorted.subList(0, Math.min(k, sorted.size()));
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
