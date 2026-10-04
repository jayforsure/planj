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
 * Home: tonight. In the morning, how last night's move went; then tonight's one move, with what
 * it has meant for your goals and a nudge to set; quick things to do; and your goals as plain
 * counts and dots. No percentages here: those wait on each goal's own page.
 */
final class HomeTab {
    private final MainActivity a;
    private final View root;
    private final LinearLayout body;
    private final TextView title, date, initial;
    private final ImageView photo;

    HomeTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_home, container, false);
        container.addView(root);
        body = root.findViewById(R.id.home_body);
        title = root.findViewById(R.id.home_title);
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
        boolean night = !java.time.LocalTime.now().isBefore(java.time.LocalTime.of(17, 0)) || java.time.LocalTime.now().isBefore(java.time.LocalTime.of(5, 0));
        title.setText(night ? "Tonight" : "Today");
        date.setText(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)));
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
        if (r.goals.isEmpty()) {
            pickGoals();
            actions();
            return;
        }
        if (r.hist.size() < OddsEngine.MIN_HISTORY) {
            learning(r.hist.size());
            actions();
            goals(r);
            return;
        }
        if (java.time.LocalTime.now().isBefore(java.time.LocalTime.of(17, 0))) lastNight(r);
        tonight(r);
        actions();
        goals(r);
    }

    /** The morning after: did you do last night's move? Said kindly either way. */
    private void lastNight(OddsTab.Result r) {
        if (r.lastMove == null || r.lastDid == null || r.lastDay == null) return;
        LinearLayout c = card();
        c.addView(words("Last night", R.color.muted, 13, 0));
        TextView what = words(OddsWords.lastNight(r.lastMove, r.lastDay, r.lastDid), R.color.text, 18, 2);
        what.setTypeface(a.getResources().getFont(R.font.display));
        what.setFontVariationSettings("'wght' 700, 'opsz' 40, 'wdth' 100");
        c.addView(what);
        TextView how = words(r.lastDid ? "✓  You did it: " + OddsWords.move(r.lastMove).toLowerCase(Locale.ENGLISH)
                : "Not this time. Tonight's another go.", r.lastDid ? R.color.accent : R.color.muted, 14, 6);
        c.addView(how);
        c.setClickable(false);
    }

    /**
     * Tonight's one move: the week of nights you did it as a ring, what followed on your own
     * days, and a nudge to set. While planj is still comparing nights, it says so.
     */
    private void tonight(OddsTab.Result r) {
        LinearLayout c = card();
        if (r.tonight == null) {
            c.addView(words("Tonight", R.color.muted, 13, 0));
            TextView t = words("Learning what your nights change", R.color.text, 19, 2);
            t.setTypeface(a.getResources().getFont(R.font.display));
            c.addView(t);
            c.addView(words("planj compares nights you put your phone down early with nights you didn't. "
                    + "Once it has seen 4 of each, it shows the one that makes your days better.", R.color.muted, 14, 8));
            c.setClickable(false);
            return;
        }
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(words("Tonight", R.color.muted, 13, 0));
        TextView what = words(OddsWords.move(r.tonight), R.color.text, 24, 2);
        what.setTypeface(a.getResources().getFont(R.font.display));
        what.setFontVariationSettings("'wght' 700, 'opsz' 40, 'wdth' 100");
        text.addView(what);
        top.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!r.tonightWeek.isEmpty()) {
            int done = 0;
            for (boolean b : r.tonightWeek) if (b) done++;
            LinearLayout week = new LinearLayout(a);
            week.setOrientation(LinearLayout.VERTICAL);
            week.setGravity(Gravity.CENTER_HORIZONTAL);
            OddsRing ring = new OddsRing(a);
            ring.setCount(done, r.tonightWeek.size());
            week.addView(ring, new LinearLayout.LayoutParams(dp(76), dp(76)));
            TextView cap = words("nights this week", R.color.muted, 11, 4);
            cap.setGravity(Gravity.CENTER);
            week.addView(cap, new LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wl.setMarginStart(dp(12));
            top.addView(week, wl);
        }
        c.addView(top);

        c.addView(words("After nights like that, on your own days:", R.color.muted, 13, 16));
        for (Goals.Goal g : r.helped) {
            OddsEngine.Move m = r.moves.get(g.id);
            c.addView(words(OddsWords.nextDay(g) + ": " + m.k + " of " + m.n + " times", R.color.text, 15, 8));
            c.addView(words("Other nights: " + m.otherK + " of " + m.otherN, R.color.muted, 13, 1));
        }

        String set = MoveReminder.isSet(a);
        if (r.tonight.equals(set)) {
            Button b = new Button(a, null, 0, R.style.Pill_Secondary);
            b.setText("✓  Reminder at " + MoveReminder.clock(MoveReminder.remindAt(r.tonight)));
            b.setOnClickListener(v -> {
                MoveReminder.cancel(a, r.tonight);
                show(r);
            });
            c.addView(b, pill());
        } else if (MoveReminder.canSet(r.tonight)) {
            Button b = new Button(a, null, 0, R.style.Pill_Primary);
            b.setText("Remind me at " + MoveReminder.clock(MoveReminder.remindAt(r.tonight)));
            b.setOnClickListener(v -> {
                MoveReminder.set(a, r.tonight);
                a.toast("planj will nudge you at " + MoveReminder.clock(MoveReminder.remindAt(r.tonight)));
                show(r);
            });
            c.addView(b, pill());
        }
        Goals.Goal opens = r.helped.get(0);
        c.setOnClickListener(v -> OddsDetailActivity.open(a, opens.outcomeId));
    }

    private LinearLayout.LayoutParams pill() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(18);
        return lp;
    }

    private void pickGoals() {
        LinearLayout c = card();
        c.addView(heading("What do you want more of?"));
        c.addView(words("Pick a goal or two, and planj shows you the one thing tonight that helps them most, from your own days.", R.color.muted, 14, 8));
        Button b = new Button(a, null, 0, R.style.Pill_Primary);
        b.setText("Pick your goals");
        b.setOnClickListener(v -> a.startActivity(new Intent(a, GoalsActivity.class)));
        c.addView(b, pill());
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

    /** Your goals: how often you reached each lately, as words and dots. */
    private void goals(OddsTab.Result r) {
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
        tl.bottomMargin = dp(4);
        body.addView(title, tl);
        for (Goals.Goal g : r.goals) body.addView(OddsTab.goalRow(a, r, g, false));
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
        TextView b = words("In a few days planj starts showing what your nights change for your goals.", R.color.muted, 14, 8);
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
