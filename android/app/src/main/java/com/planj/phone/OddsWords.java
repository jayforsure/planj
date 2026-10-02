package com.planj.phone;

import android.content.Context;

/** Forecasts in plain words: the sentence, a short title, and what tipped it. */
final class OddsWords {
    private OddsWords() {}

    /** "You're off all devices by 1am tonight" */
    static String sentence(Context ctx, String outcomeId, String question) {
        switch (outcomeId) {
            case "off_by_1am": return "You're off all devices by 1am tonight";
            case "quiet_7h": return "You get 7+ hours device-free tonight";
            case "heavy_screen": return "Tomorrow is a heavier screen day than usual";
            case "social_2h": return "You spend 2h+ on social apps tomorrow";
            case "up_by_8": return "You're up by 8 tomorrow";
            case OddsEngine.FOCUS: return "You get 2h+ focus on your PC tomorrow";
            case OddsEngine.CLASS: {
                TarcTimetable.OnDay c = firstClassTomorrow(ctx);
                return c == null ? "You make it to your first class"
                        : "You make it to " + c.course.name + " at " + Routines.clock(c.lesson.startMin) + " tomorrow";
            }
            default: {
                Routines.Routine rt = Routines.Routine.parse(outcomeId);
                if (rt == null) return question;
                String name = RoutineNames.name(ctx, rt), when = rt.window();
                if (rt.kind == Routines.Kind.FREE && name.equals(rt.kind.defaultName)) return "Your phone stays down " + when + " tomorrow";
                if (rt.kind == Routines.Kind.AWAY && name.equals(rt.kind.defaultName)) return "You're out " + when + " tomorrow";
                if (rt.kind == Routines.Kind.AT) return "You're at " + name + " " + when + " tomorrow";
                return name + " " + when + " tomorrow";
            }
        }
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

    /** "Less likely than usual, because tomorrow's a weekend day", or "Usually 53%". */
    static String why(OddsEngine.Forecast fc) {
        String b = "recent".equals(fc.leverId) ? "you made " + fc.sideK + " of your last " + fc.sideN + " class days"
                : because(fc.leverId, fc.leverSide);
        if (b == null || Math.abs(fc.prob - fc.base) < 0.03) return "Usually " + Math.round(fc.base * 100) + "%";
        return (fc.prob < fc.base ? "Less" : "More") + " likely than usual, because " + b;
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

    /** A side of a signal as a short label: "Before a weekend day". */
    static String side(String leverId, boolean yes) {
        String b = because(leverId, yes);
        if (b == null) return "";
        return Character.toUpperCase(b.charAt(0)) + b.substring(1);
    }
}
