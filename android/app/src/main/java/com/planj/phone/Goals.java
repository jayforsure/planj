package com.planj.phone;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What you want more of. planj gives odds only for these, always as your chance of getting
 * there, with the thing you can do tonight that changes it most.
 */
final class Goals {
    private static final String PREFS = "planj_goals";

    static final class Goal {
        final String id, outcomeId, name, about;
        final boolean good;  // the answer to the outcome's question that is the goal
        final int icon;
        final String needs;  // the connector it needs, or null

        Goal(String id, String outcomeId, boolean good, String name, String about, int icon, String needs) {
            this.id = id;
            this.outcomeId = outcomeId;
            this.good = good;
            this.name = name;
            this.about = about;
            this.icon = icon;
            this.needs = needs;
        }

        /** Your chance of reaching it, from a forecast of its question. */
        double chance(OddsEngine.Forecast fc) {
            return good ? fc.prob : 1 - fc.prob;
        }

        double usual(OddsEngine.Forecast fc) {
            return good ? fc.base : 1 - fc.base;
        }

        boolean ready(Context ctx) {
            return needs == null || Connectors.get(needs).connected(ctx);
        }
    }

    static final List<Goal> ALL = List.of(
            new Goal("sleep", "quiet_7h", true, "A full night", "7 hours or more with your phone down", R.drawable.ic_moon, null),
            new Goal("scroll", "social_2h", false, "Less scrolling", "Under 2 hours a day on social apps", R.drawable.ic_people, null),
            new Goal("early", "up_by_8", true, "Up by 8", "Your first look at your phone before 8am", R.drawable.ic_sun, null),
            new Goal("study", OddsEngine.FOCUS, true, "Focused PC time", "2 hours or more of focused work on your PC", R.drawable.ic_monitor, "pc"),
            new Goal("class", OddsEngine.CLASS, true, "Make my classes", "Being at the first class of each class day", R.drawable.ic_school, "tarc"));

    private Goals() {}

    static Goal forOutcome(String outcomeId) {
        for (Goal g : ALL) if (g.outcomeId.equals(outcomeId)) return g;
        return null;
    }

    /** The goals you picked that planj can follow; until you pick, all it can follow but Up by 8. */
    static List<Goal> chosen(Context ctx) {
        Set<String> ids = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("ids", null);
        List<Goal> out = new ArrayList<>();
        for (Goal g : ALL) {
            if ((ids != null ? ids.contains(g.id) : !g.id.equals("early")) && g.ready(ctx)) out.add(g);
        }
        return out;
    }

    static void set(Context ctx, Set<String> ids) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("ids", new HashSet<>(ids)).apply();
    }
}
