package com.planj.phone;

import android.content.Context;

/** planj in plain words: tonight's move, how last night went, how often lately, and its guess for tomorrow. No percentages. */
final class OddsWords {
    private OddsWords() {}

    /** The thing to do tonight: "Off your phone by 1am". */
    static String move(String moveId) {
        switch (moveId) {
            case "bed_23": return "Off your phone by 11pm";
            case "bed_00": return "Off your phone by midnight";
            case "bed_01": return "Off your phone by 1am";
            case "bed_02": return "Off your phone by 2am";
            case "full_night": return "A full night, 7h+ phone down";
            case "no_late_watch": return "No watching on your PC after 11pm";
            default: return moveId;
        }
    }

    /** planj's guess for tomorrow in a word or two: no percentages on the main screens. */
    static String guess(double chance) {
        if (chance >= 0.65) return "Probably yes";
        if (chance <= 0.35) return "Probably not";
        return "50/50";
    }

    /** Why planj guesses that, in plain words. */
    static String guessWhy(Goals.Goal g, OddsEngine.Forecast fc) {
        String b = "recent".equals(fc.leverId) ? "you made " + fc.sideK + " of your last " + fc.sideN + " class days"
                : because(fc.leverId, fc.leverSide);
        if (b == null || Math.abs(g.chance(fc) - g.usual(fc)) < 0.03) return "planj's guess, from your usual days";
        return "planj's guess, because " + b;
    }

    /** "3 of the last 7 days", "Made 4 of your last 6 class days" */
    static String lately(Goals.Goal g, java.util.List<Boolean> days) {
        int k = 0;
        for (boolean b : days) if (b) k++;
        if (g.id.equals("class")) return "Made " + k + " of your last " + days.size() + " class days";
        return k + " of the last " + days.size() + (days.size() == 1 ? " day" : " days");
    }

    /** A goal as it shows the morning or day after a night: "Under 2h on social the next day" */
    static String nextDay(Goals.Goal g) {
        switch (g.id) {
            case "sleep": return "A full night";
            case "scroll": return "Under 2h on social the next day";
            case "early": return "Up by 8 the next morning";
            case "study": return "2h+ PC focus the next day";
            case "class": return "At your first class the next day";
            default: return g.name;
        }
    }

    /** "12:41am" for an hour of the day such as 0.68 or 23.5. */
    static String clockHour(double hour) {
        int min = (int) Math.round((((hour % 24) + 24) % 24) * 60);
        return MoveReminder.clock(min % (24 * 60));
    }

    /** What last night looked like for a move: "Phone down at 12:41am" */
    static String lastNight(String moveId, OddsEngine.Day day, boolean did) {
        if (moveId.startsWith("bed_") && day.quietStartHour != null) return "Phone down at " + clockHour(day.quietStartHour);
        if (moveId.equals("full_night") && day.quietH != null) {
            int min = (int) Math.round(day.quietH * 60);
            return min / 60 + "h " + min % 60 + "m with your phone down";
        }
        if (moveId.equals("no_late_watch")) return did ? "No watching after 11pm" : "Watching after 11pm";
        return move(moveId);
    }

    /** "Off devices by 1am": for small cards and list rows. */
    static String title(Context ctx, String outcomeId, String question) {
        switch (outcomeId) {
            case "off_by_1am": return "Off devices by 1am";
            case "quiet_7h": return "7h+ device-free";
            case "heavy_screen": return "Heavier screen day";
            case "social_2h": return "2h+ on social";
            case "up_by_8": return "Up by 8";
            case OddsEngine.FOCUS: return "2h+ PC focus";
            case OddsEngine.CLASS: {
                TarcTimetable.OnDay c = firstClassTomorrow(ctx);
                return c == null ? "Make your first class" : "Make your " + Routines.clock(c.lesson.startMin) + " class";
            }
            default: {
                Routines.Routine rt = Routines.Routine.parse(outcomeId);
                return rt == null ? question : RoutineNames.name(ctx, rt) + " " + rt.window();
            }
        }
    }

    private static TarcTimetable.OnDay firstClassTomorrow(Context ctx) {
        java.util.List<TarcTimetable.OnDay> on = TarcTimetable.on(ctx, java.time.LocalDate.now().plusDays(1));
        return on.isEmpty() ? null : on.get(0);
    }

    /** What a side of a signal means, as the second half of "because …". */
    static String because(String leverId, boolean yes) {
        if (leverId == null) return null;
        switch (leverId) {
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
}
