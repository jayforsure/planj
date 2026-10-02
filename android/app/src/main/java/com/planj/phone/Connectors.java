package com.planj.phone;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything planj learns from: built in (this phone, your PC, places) and added (calendar,
 * TAR UMT). A connector earns its place only by adding a question planj can forecast, or the
 * answer key that checks one. Each has its own page saying what it adds to your odds.
 */
final class Connectors {
    static final class Connector {
        final String id, about, checkedAgainst;
        final int glyph;
        final boolean builtIn;

        Connector(String id, boolean builtIn, String about, String checkedAgainst, int glyph) {
            this.id = id;
            this.builtIn = builtIn;
            this.about = about;
            this.checkedAgainst = checkedAgainst;
            this.glyph = glyph;
        }

        String name(Context ctx) {
            switch (id) {
                case "phone": return "This phone";
                case "pc": return "Your PC";
                case "places": return "Places";
                case "calendar": return "Calendar";
                default: return "TAR UMT";
            }
        }

        /** The service's own icon when it is on this phone, else a drawn glyph (null). */
        Drawable picture(Context ctx) {
            return id.equals("tarc") ? AppPalette.icon(ctx, TarcActivity.TARC_APP) : null;
        }

        boolean connected(Context ctx) {
            switch (id) {
                case "phone": return usageAccess(ctx);
                case "pc": return RelaySync.confirmedMs(ctx) > 0;
                case "places": return Places.enabled(ctx) && Places.hasForeground(ctx) && Places.hasBackground(ctx);
                case "calendar": return Agenda.allowed(ctx);
                default: return TarcStore.connected(ctx);
            }
        }

        /** "Connected · updated today at 22:32", "Not connected", or what's missing. */
        String status(Context ctx) {
            switch (id) {
                case "pc": {
                    long heard = RelaySync.confirmedMs(ctx);
                    return heard == 0 ? "Not connected" : DeviceNames.pc(ctx) + " · last heard " + TarcActivity.when(heard);
                }
                case "places":
                    if (Places.enabled(ctx) && !connected(ctx)) return "Needs “Allow all the time”";
                    if (!connected(ctx)) return "Not connected";
                    int n = Places.count(ctx);
                    return "Connected · " + n + (n == 1 ? " place" : " places");
                case "tarc": {
                    if (!connected(ctx)) return "Not connected";
                    long read = TarcStore.state(ctx).optLong("read", 0);
                    return read == 0 ? "Connected" : "Connected · updated " + TarcActivity.when(read);
                }
                default:
                    return connected(ctx) ? "Connected" : "Not connected";
            }
        }

        void open(Activity a) {
            if (id.equals("tarc")) a.startActivity(new Intent(a, TarcActivity.class));
            else a.startActivity(new Intent(a, ConnectorActivity.class).putExtra(ConnectorActivity.EXTRA_ID, id));
        }
    }

    static final List<Connector> ALL = List.of(
            new Connector("phone", true, "Your screen, unlocks and nights, from Android's own record",
                    "Android's own usage record", R.drawable.ic_phone),
            new Connector("pc", true, "What's in front on your computer: focus, watching and more",
                    "What the planj tracker sees on your PC", R.drawable.ic_monitor),
            new Connector("places", true, "Where you are, so planj can learn your arrivals",
                    "Your location, on this phone", R.drawable.ic_place),
            new Connector("calendar", false, "Your phone's calendars, Google Calendar included",
                    "Nothing yet: plans shape other forecasts", R.drawable.ic_journal),
            new Connector("tarc", false, "Your TAR UMT student intranet: classes, deadlines and more",
                    "TAR UMT's own dashboard and attendance", R.drawable.ic_school));

    private Connectors() {}

    static Connector get(String id) {
        for (Connector c : ALL) if (c.id.equals(id)) return c;
        return ALL.get(0);
    }

    static List<Connector> connected(Context ctx) {
        List<Connector> out = new ArrayList<>();
        for (Connector c : ALL) if (c.connected(ctx)) out.add(c);
        return out;
    }

    static boolean usageAccess(Context ctx) {
        AppOpsManager ops = ctx.getSystemService(AppOpsManager.class);
        int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    static void fillRow(Context ctx, ListRow row, Connector c, String subtitle) {
        Drawable d = c.picture(ctx);
        if (d != null) row.setImage(d);
        else row.setIcon(c.glyph);
        row.setTitle(c.name(ctx));
        row.setSubtitle(subtitle);
    }
}
