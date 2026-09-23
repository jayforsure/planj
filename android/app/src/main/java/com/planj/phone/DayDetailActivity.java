package com.planj.phone;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.time.LocalDate;

/** Every app used on one day, most used first. */
public class DayDetailActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_day);
        LocalDate day = LocalDate.parse(getIntent().getStringExtra("day"));
        findViewById(R.id.back).setOnClickListener(v -> finish());

        DayUsage usage = DayUsage.load(this, day);
        ((TextView) findViewById(R.id.title)).setText(day.equals(LocalDate.now()) ? "Today" : Fmt.longDate(day));
        ((TextView) findViewById(R.id.subtitle)).setText(Fmt.duration(usage.screenMs) + " on screen · "
                + usage.unlocks + " unlocks" + Fmt.quietSuffix(DayUsage.quiet(this, day)));
        AppRows.fill(this, findViewById(R.id.list), usage, Integer.MAX_VALUE);
        fillContent(day);
    }

    /** Premium: the videos, posts and pages that held attention that day, longest first. */
    private void fillContent(LocalDate day) {
        java.util.List<ContentStore.Item> items = ContentStore.forDay(this, day);
        java.util.Map<String, long[]> byText = new java.util.LinkedHashMap<>(); // app|text -> {ms, actions}
        for (ContentStore.Item it : items) {
            long[] t = byText.computeIfAbsent(it.app + "|" + it.text, k -> new long[2]);
            t[0] += it.dwellMs;
            if (it.action != null) t[1]++;
        }
        byText.entrySet().removeIf(e -> e.getValue()[0] < 5_000);
        if (byText.isEmpty()) return;
        java.util.List<java.util.Map.Entry<String, long[]>> ranked = new java.util.ArrayList<>(byText.entrySet());
        ranked.sort((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]));

        findViewById(R.id.content_card).setVisibility(android.view.View.VISIBLE);
        android.widget.LinearLayout list = findViewById(R.id.content_list);
        float density = getResources().getDisplayMetrics().density;
        int shown = 0;
        for (java.util.Map.Entry<String, long[]> e : ranked) {
            if (shown++ == 30) break;
            String[] parts = e.getKey().split("\\|", 2);
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (9 * density), 0, (int) (9 * density));

            android.view.View dot = new android.view.View(this);
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            shape.setColor(AppPalette.color(parts[0]));
            dot.setBackground(shape);
            android.widget.LinearLayout.LayoutParams dp = new android.widget.LinearLayout.LayoutParams((int) (8 * density), (int) (8 * density));
            dp.rightMargin = (int) (12 * density);
            row.addView(dot, dp);

            android.widget.LinearLayout col = new android.widget.LinearLayout(this);
            col.setOrientation(android.widget.LinearLayout.VERTICAL);
            TextView text = new TextView(this);
            text.setText(parts[1]);
            text.setTextColor(getColor(R.color.text));
            text.setTextSize(14);
            text.setMaxLines(2);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(text);
            TextView meta = new TextView(this);
            meta.setText(AppPalette.label(this, parts[0]) + " · " + ContentStore.topic(parts[1])
                    + (e.getValue()[1] > 0 ? " · " + e.getValue()[1] + (e.getValue()[1] == 1 ? " action" : " actions") : ""));
            meta.setTextColor(getColor(R.color.muted));
            meta.setTextSize(12);
            col.addView(meta);
            row.addView(col, new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView dwell = new TextView(this);
            dwell.setText(Fmt.duration(e.getValue()[0]));
            dwell.setTextColor(getColor(R.color.muted));
            dwell.setTextSize(13);
            dwell.setPadding((int) (12 * density), 0, 0, 0);
            row.addView(dwell);
            list.addView(row);
        }
    }
}
