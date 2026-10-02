package com.planj.phone;

import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Home: tomorrow's odds at a glance. The three most telling odds in plain words, and how often
 * planj has been right. Everything else is one tap away, under All odds.
 */
final class HomeTab {
    private final MainActivity a;
    private final View root;
    private final LinearLayout cards;
    private final TextView date, proof;
    private final Button all;

    HomeTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_home, container, false);
        container.addView(root);
        cards = root.findViewById(R.id.home_cards);
        date = root.findViewById(R.id.home_date);
        proof = root.findViewById(R.id.home_proof);
        all = root.findViewById(R.id.home_all);
        all.setOnClickListener(v -> a.showAllOdds());
        proof.setOnClickListener(v -> a.showAllOdds());
        date.setText(LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)));
        proof.setVisibility(View.GONE);
        all.setVisibility(View.GONE);
    }

    View view() {
        return root;
    }

    void show(OddsTab.Result r) {
        date.setText(LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)));
        cards.removeAllViews();
        if (!Connectors.usageAccess(a)) {
            card(null, "planj can't see your phone yet",
                    "It learns your days from Android's usage access: which apps, when the screen is on, when you put it down. Nothing leaves this phone unencrypted.",
                    "Allow usage access", () -> a.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
            proof.setVisibility(View.GONE);
            all.setVisibility(View.GONE);
            return;
        }
        if (r.forecasts.isEmpty()) {
            learning(Math.min(r.hist.size(), OddsEngine.MIN_HISTORY));
            proof.setVisibility(View.GONE);
            all.setVisibility(View.GONE);
            return;
        }
        for (OddsEngine.Forecast fc : top(r.forecasts, 3)) {
            View c = card(Math.round(fc.prob * 100) + "%", sentence(fc), why(fc), null, null);
            c.setOnClickListener(v -> a.showAllOdds());
        }
        int hits = 0, n = 0;
        if (r.live != null) for (OddsEngine.Record rec : r.live.values()) {
            hits += rec.hits;
            n += rec.n;
        }
        proof.setText(n == 0 ? "Every forecast is checked against what really happens, the next morning."
                : "Right " + hits + " of " + n + " times so far. Every forecast is checked the next morning.");
        proof.setVisibility(View.VISIBLE);
        all.setText(r.forecasts.size() > 3 ? "All " + r.forecasts.size() + " odds" : "All odds");
        all.setVisibility(View.VISIBLE);
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

    /** The forecast as a plain sentence about you. */
    String sentence(OddsEngine.Forecast fc) {
        switch (fc.outcome.id) {
            case "off_by_1am": return "You're off all devices by 1am tonight";
            case "quiet_7h": return "You get 7+ hours device-free tonight";
            case "heavy_screen": return "Tomorrow is a heavier screen day than usual";
            case "social_2h": return "You spend 2h+ on social apps tomorrow";
            case "up_by_8": return "You're up by 8 tomorrow";
            case OddsEngine.FOCUS: return "You get 2h+ focus on your PC tomorrow";
            default: {
                Routines.Routine rt = Routines.Routine.parse(fc.outcome.id);
                if (rt == null) return fc.outcome.question;
                String name = RoutineNames.name(a, rt), when = rt.window();
                if (rt.kind == Routines.Kind.FREE && name.equals(rt.kind.defaultName)) return "Your phone stays down " + when + " tomorrow";
                if (rt.kind == Routines.Kind.AWAY && name.equals(rt.kind.defaultName)) return "You're out " + when + " tomorrow";
                if (rt.kind == Routines.Kind.AT) return "You're at " + name + " " + when + " tomorrow";
                return name + " " + when + " tomorrow";
            }
        }
    }

    /** Why, in plain words: more or less likely than usual, and because of what. */
    static String why(OddsEngine.Forecast fc) {
        String usual = "usually " + Math.round(fc.base * 100) + "%";
        String because = because(fc.leverText);
        if (because == null || Math.abs(fc.prob - fc.base) < 0.03) return Character.toUpperCase(usual.charAt(0)) + usual.substring(1);
        return (fc.prob < fc.base ? "Less" : "More") + " likely than usual, because " + because + " (" + usual + ")";
    }

    /** What tipped a forecast, as the second half of "because …". */
    private static String because(String leverText) {
        if (leverText == null) return null;
        for (OddsEngine.Lever l : OddsEngine.LEVERS) {
            boolean yes = leverText.equals(l.whenTrue);
            if (!yes && !leverText.equals(l.whenFalse)) continue;
            switch (l.id) {
                case "short_night": return yes ? "last night was short" : "last night was a full one";
                case "late_phone": return yes ? "you were on your phone past midnight" : "you were off your phone by midnight";
                case "social_heavy": return yes ? "you spent 2h+ on social today" : "you were under 2h on social today";
                case "many_unlocks": return yes ? "you unlocked your phone more than usual today" : "you unlocked your phone less than usual today";
                case "pc_focus_day": return yes ? "you had a focused day on your PC" : "you had little focus on your PC today";
                case "pc_watch_late": return yes ? "you watched past 11pm" : "you didn't watch late";
                case "pc_watch_heavy": return yes ? "you watched over an hour today" : "you watched under an hour today";
                case "out_at_place": return yes ? "you were out today" : "you stayed home today";
                case "class_morning": return yes ? "you have a morning class" : "you have no morning class";
                case "early_plans": return yes ? "you have plans before 10am" : "your morning is free";
                case "charged": return yes ? "your phone charges overnight" : "your phone isn't charging overnight";
                case "weekend_next": return yes ? "tomorrow's a weekend day" : "tomorrow's a weekday";
                default: return null;
            }
        }
        return null;
    }

    private void learning(int days) {
        LinearLayout c = cardShell();
        TextView t = new TextView(a, null, 0, R.style.Heading);
        t.setText("Getting to know you");
        c.addView(t);
        TextView big = bigNumber("Day " + Math.max(1, days) + " of " + OddsEngine.MIN_HISTORY);
        ((LinearLayout.LayoutParams) big.getLayoutParams()).topMargin = dp(14);
        c.addView(big);
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(OddsEngine.MIN_HISTORY);
        bar.setProgress(Math.max(1, days));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(a.getColor(R.color.accent)));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(a.getColor(R.color.surface_alt)));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        bl.topMargin = dp(12);
        c.addView(bar, bl);
        TextView body = text("In a few days planj starts telling you tomorrow's odds: when you'll put your phone down, "
                + "how long you'll stay off it, and more. Every one is checked the next morning.", R.color.muted, 14);
        ((LinearLayout.LayoutParams) body.getLayoutParams()).topMargin = dp(14);
        c.addView(body);
    }

    /** A card: a big number (or none), a sentence, a quieter line, and maybe a button. */
    private View card(String number, String sentence, String why, String action, Runnable onAction) {
        LinearLayout c = cardShell();
        if (number != null) c.addView(bigNumber(number));
        TextView s = text(sentence, R.color.text, number == null ? 19 : 17);
        if (number == null) s.setTypeface(a.getResources().getFont(R.font.display));
        ((LinearLayout.LayoutParams) s.getLayoutParams()).topMargin = dp(number == null ? 0 : 6);
        c.addView(s);
        TextView w = text(why, R.color.muted, 13);
        ((LinearLayout.LayoutParams) w.getLayoutParams()).topMargin = dp(6);
        c.addView(w);
        if (action != null) {
            Button b = new Button(a, null, 0, R.style.Pill_Primary);
            b.setText(action);
            b.setOnClickListener(v -> onAction.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            lp.topMargin = dp(18);
            c.addView(b, lp);
        }
        return c;
    }

    private LinearLayout cardShell() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card_clickable);
        c.setPadding(dp(20), dp(20), dp(20), dp(20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        cards.addView(c, lp);
        return c;
    }

    private TextView bigNumber(String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(a.getColor(R.color.text));
        t.setTextSize(40);
        t.setTypeface(a.getResources().getFont(R.font.display));
        t.setFontVariationSettings("'wght' 800, 'opsz' 96, 'wdth' 100");
        t.setLetterSpacing(-0.03f);
        t.setIncludeFontPadding(false);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private TextView text(String s, int color, int sp) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(a.getColor(color));
        t.setTextSize(sp);
        t.setLineSpacing(dp(3), 1f);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
