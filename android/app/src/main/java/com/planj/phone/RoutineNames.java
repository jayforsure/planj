package com.planj.phone;

import android.content.Context;

/** The names a person gave their routines ("Gym"), kept on this phone. */
final class RoutineNames {
    private static final String PREFS = "planj_routines";

    private RoutineNames() {}

    /** Names follow the routine's kind and days, so a small shift in its window keeps the name. */
    private static String key(Routines.Routine r) {
        return r.kind.name() + (r.place == null ? "" : "-" + r.place) + "_" + r.mask + "_" + (r.start / 60);
    }

    static String name(Context ctx, Routines.Routine r) {
        String n = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(r), null);
        if (n != null && !n.isEmpty()) return n;
        return r.kind == Routines.Kind.AT ? Places.labelFor(ctx, r.place) : r.kind.defaultName;
    }

    static void set(Context ctx, Routines.Routine r, String name) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key(r), name.trim()).apply();
    }

    /** "Gym · Tue, Thu 18:45–20:45" */
    static String title(Context ctx, Routines.Routine r) {
        return name(ctx, r) + " · " + r.days() + " " + r.window();
    }
}
