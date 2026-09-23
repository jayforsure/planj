package com.planj.phone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Fills a column with app rows: icon, name, time and a share bar in the app's colour. */
final class AppRows {
    private AppRows() {}

    static void fill(Activity a, LinearLayout column, DayUsage usage, int limit) {
        column.removeAllViews();
        List<Map.Entry<String, Long>> ranked = usage.ranked();
        long total = Math.max(usage.appTotalMs(), 1);
        LayoutInflater inflater = a.getLayoutInflater();
        int shown = 0;
        for (Map.Entry<String, Long> e : ranked) {
            if (shown++ == limit) break;
            String pkg = e.getKey();
            View row = inflater.inflate(R.layout.item_app_row, column, false);
            ImageView icon = row.findViewById(R.id.app_icon);
            Drawable d = AppPalette.icon(a, pkg);
            if (d != null) icon.setImageDrawable(d);
            else icon.setBackgroundColor(AppPalette.color(pkg));
            ((TextView) row.findViewById(R.id.app_name)).setText(AppPalette.label(a, pkg));
            ((TextView) row.findViewById(R.id.app_time)).setText(
                    Fmt.duration(e.getValue()) + " · " + Math.round(100.0 * e.getValue() / total) + "%");

            View track = row.findViewById(R.id.app_track);
            View fill = row.findViewById(R.id.app_fill);
            fill.getBackground().mutate().setTint(AppPalette.color(pkg));
            float share = (float) e.getValue() / total;
            track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int width = Math.max(4, Math.round((r - l) * share));
                if (fill.getLayoutParams().width != width) {
                    fill.getLayoutParams().width = width;
                    fill.requestLayout();
                }
            });

            LocalDate day = usage.day;
            row.setOnClickListener(v -> a.startActivity(new Intent(a, AppDetailActivity.class)
                    .putExtra("pkg", pkg).putExtra("day", day.toString())));
            column.addView(row);
        }
        if (ranked.isEmpty()) {
            TextView empty = new TextView(a);
            empty.setText("Nothing recorded yet");
            empty.setTextColor(a.getColor(R.color.muted));
            empty.setPadding(0, 16, 0, 16);
            column.addView(empty);
        }
    }
}
