package com.planj.phone;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** One app on one day: its week, and every session that day. */
public class AppDetailActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app);
        String pkg = getIntent().getStringExtra("pkg");
        LocalDate day = LocalDate.parse(getIntent().getStringExtra("day"));
        findViewById(R.id.back).setOnClickListener(v -> finish());

        Drawable icon = AppPalette.icon(this, pkg);
        if (icon != null) ((ImageView) findViewById(R.id.icon)).setImageDrawable(icon);
        ((TextView) findViewById(R.id.title)).setText(AppPalette.label(this, pkg));

        DayUsage usage = DayUsage.load(this, day);
        long ms = usage.appMs.getOrDefault(pkg, 0L);
        long share = usage.appTotalMs() == 0 ? 0 : Math.round(100.0 * ms / usage.appTotalMs());
        ((TextView) findViewById(R.id.subtitle)).setText(Fmt.duration(ms) + " on " + Fmt.shortDate(day) + " · " + share + "% of app time");

        float[] hours = new float[7];
        String[] labels = new String[7];
        for (int i = 0; i < 7; i++) {
            LocalDate d = day.minusDays(6 - i);
            hours[i] = DayUsage.load(this, d).appMs.getOrDefault(pkg, 0L) / 3600000f;
            labels[i] = d.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)).substring(0, 1);
        }
        BarChartView chart = findViewById(R.id.chart);
        chart.setHighlightColor(AppPalette.color(pkg));
        chart.setData(hours, labels);

        LinearLayout sessions = findViewById(R.id.sessions);
        int count = 0;
        float density = getResources().getDisplayMetrics().density;
        for (int i = usage.sessions.size() - 1; i >= 0; i--) {
            DayUsage.Session s = usage.sessions.get(i);
            if (!s.pkg.equals(pkg) || s.endMs - s.startMs < 30_000) continue;
            count++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, (int) (10 * density), 0, (int) (10 * density));
            TextView when = new TextView(this);
            when.setText(Fmt.clock(s.startMs) + " – " + Fmt.clock(s.endMs));
            when.setTextColor(getColor(R.color.text));
            when.setTextSize(14);
            row.addView(when, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            TextView len = new TextView(this);
            len.setText(Fmt.duration(s.endMs - s.startMs));
            len.setTextColor(getColor(R.color.muted));
            len.setTextSize(13);
            row.addView(len);
            sessions.addView(row);
        }
        ((TextView) findViewById(R.id.sessions_title)).setText(
                count == 0 ? "NO SESSIONS OVER 30 SECONDS" : count + (count == 1 ? " SESSION" : " SESSIONS") + " · LATEST FIRST");
    }
}
