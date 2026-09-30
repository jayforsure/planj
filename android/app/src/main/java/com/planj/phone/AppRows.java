package com.planj.phone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Fills a column with phone app rows, in the same row as every other list: icon, name, time. */
final class AppRows {
    private AppRows() {}

    static void fill(Activity a, LinearLayout column, DayUsage usage, int limit) {
        column.removeAllViews();
        List<Map.Entry<String, Long>> ranked = usage.ranked();
        int shown = 0;
        for (Map.Entry<String, Long> e : ranked) {
            if (shown++ == limit) break;
            String pkg = e.getKey();
            String name = AppPalette.label(a, pkg);
            ListRow row = new ListRow(a);
            Drawable d = AppPalette.icon(a, pkg);
            if (d != null) row.setImage(d);
            else row.setLetter(name);
            row.setTitle(name);
            row.setValue(Fmt.shortDuration(e.getValue()), false);
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
