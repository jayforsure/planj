package com.planj.phone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

/**
 * The connectors planj offers: other places your week already lives, which you can let planj
 * use. Only ones that work are listed; each has its own page to connect and disconnect.
 */
final class Connectors {
    static final class Connector {
        final String id, name, about;
        final int glyph;

        Connector(String id, String name, String about, int glyph) {
            this.id = id;
            this.name = name;
            this.about = about;
            this.glyph = glyph;
        }

        /** The service's own icon when it is on this phone, else a drawn glyph (null). */
        Drawable picture(Context ctx) {
            return id.equals("tarc") ? AppPalette.icon(ctx, TarcActivity.TARC_APP) : null;
        }

        boolean connected(Context ctx) {
            switch (id) {
                case "tarc": return TarcStore.connected(ctx);
                case "calendar": return Agenda.allowed(ctx);
                default: return false;
            }
        }

        /** "Connected · updated today at 22:32" or "Connected". */
        String status(Context ctx) {
            if (!connected(ctx)) return "Not connected";
            if (id.equals("tarc")) {
                long read = TarcStore.state(ctx).optLong("read", 0);
                return read == 0 ? "Connected" : "Connected · updated " + TarcActivity.when(read);
            }
            return "Connected";
        }

        void open(Activity a) {
            a.startActivity(new Intent(a, id.equals("tarc") ? TarcActivity.class : CalendarActivity.class));
        }
    }

    static final List<Connector> ALL = List.of(
            new Connector("tarc", "TAR UMT", "Your student intranet: timetable, attendance, exams, results and deadlines", R.drawable.ic_school),
            new Connector("calendar", "Calendar", "Your phone's calendars, including Google Calendar", R.drawable.ic_journal));

    private Connectors() {}

    static List<Connector> connected(Context ctx) {
        List<Connector> out = new ArrayList<>();
        for (Connector c : ALL) if (c.connected(ctx)) out.add(c);
        return out;
    }

    static void fillRow(Context ctx, ListRow row, Connector c, String subtitle) {
        Drawable d = c.picture(ctx);
        if (d != null) row.setImage(d);
        else row.setIcon(c.glyph);
        row.setTitle(c.name);
        row.setSubtitle(subtitle);
    }
}
