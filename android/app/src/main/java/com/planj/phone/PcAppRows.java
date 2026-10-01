package com.planj.phone;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.widget.LinearLayout;

import java.util.List;

/** PC apps and websites, in the same row as every other list: icon, name, time. */
final class PcAppRows {
    private PcAppRows() {}

    static void fill(Activity a, LinearLayout list, List<PcDays.App> apps, int limit, java.time.LocalDate day) {
        list.removeAllViews();
        if (apps == null) return;
        for (int i = 0; i < Math.min(limit, apps.size()); i++) {
            ListRow row = row(a, list, apps.get(i));
            String name = apps.get(i).name;
            row.setOnClickListener(v -> a.startActivity(PcAppActivity.open(a, name, day)));
            list.addView(row);
        }
    }

    private static final java.util.Set<String> FETCHING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static ListRow row(Activity a, LinearLayout list, PcDays.App x) {
        ListRow row = new ListRow(a);
        row.setTag(x.name);
        row.setTitle(x.name);
        row.setValue(Fmt.shortDuration(x.minutes * 60_000L), false);
        int glyph = glyph(x.name);
        if (glyph != 0) {
            row.setIcon(glyph);
            return row;
        }
        row.setLetter(x.name);
        Bitmap cached = PcIcons.cached(a, x.name);
        if (cached != null) row.setImage(new BitmapDrawable(a.getResources(), cached));
        if ((cached == null ? PcIcons.known(x.name) : PcIcons.worthRetry(x.name, cached)) && FETCHING.add(x.name)) {
            new Thread(() -> {
                Bitmap b = PcIcons.fetch(a, x.name);
                FETCHING.remove(x.name);
                if (b == null || (cached != null && b.getWidth() <= cached.getWidth())) return;
                // the list may have been rebuilt meanwhile, so update whichever row shows this name now
                a.runOnUiThread(() -> {
                    for (int i = 0; i < list.getChildCount(); i++) {
                        android.view.View v = list.getChildAt(i);
                        if (x.name.equals(v.getTag()) && v instanceof ListRow) ((ListRow) v).setImage(new BitmapDrawable(a.getResources(), b));
                    }
                });
            }).start();
        }
        return row;
    }

    /** Rows that stand for a group rather than one program get a drawn glyph instead. */
    private static int glyph(String name) {
        switch (name) {
            case "Other websites": return R.drawable.ic_globe;
            case "Windows":
            case "Desktop": return R.drawable.ic_monitor;
            default: return 0;
        }
    }

    /** The app's or site's icon on a row that stays on screen and changes what it shows (the live row). */
    static void bindIcon(Activity a, ListRow row, String name) {
        if (name.equals(row.getTag())) return; // already showing this one
        row.setTag(name);
        int glyph = glyph(name);
        if (glyph != 0) {
            row.setIcon(glyph);
            return;
        }
        row.setLetter(name);
        Bitmap cached = PcIcons.cached(a, name);
        if (cached != null) {
            row.setImage(new BitmapDrawable(a.getResources(), cached));
        } else if (PcIcons.known(name) && FETCHING.add(name)) {
            new Thread(() -> {
                Bitmap b = PcIcons.fetch(a, name);
                FETCHING.remove(name);
                if (b != null) a.runOnUiThread(() -> {
                    if (name.equals(row.getTag())) row.setImage(new BitmapDrawable(a.getResources(), b));
                });
            }).start();
        }
    }

}
