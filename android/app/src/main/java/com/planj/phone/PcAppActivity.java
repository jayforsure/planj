package com.planj.phone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** One app or website on the PC: its week, what it counts as (changeable), and its sessions. */
public class PcAppActivity extends Activity {
    private static final String EXTRA_NAME = "name", EXTRA_DAY = "day";

    static Intent open(Context ctx, String name, LocalDate day) {
        return new Intent(ctx, PcAppActivity.class).putExtra(EXTRA_NAME, name).putExtra(EXTRA_DAY, day.toString());
    }

    private String name;
    private LocalDate day;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pc_app);
        name = getIntent().getStringExtra(EXTRA_NAME);
        day = LocalDate.parse(getIntent().getStringExtra(EXTRA_DAY));
        findViewById(R.id.back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.title)).setText(name);
        showIcon();
        render();
    }

    private void render() {
        int minutes = 0;
        String cat = "other";
        List<PcDays.App> apps = PcDays.apps(this, day);
        if (apps != null) {
            for (PcDays.App a : apps) {
                if (a.name.equals(name)) {
                    minutes = a.minutes;
                    cat = a.cat;
                }
            }
        }
        String chosen = PcCategories.chosen(this, name);
        if (chosen != null) cat = chosen; // the PC catches up within minutes; show the choice now
        String when = day.equals(LocalDate.now()) ? "Today" : day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH));
        ((TextView) findViewById(R.id.subtitle)).setText(when + " · " + Fmt.shortDuration(minutes * 60_000L) + " on " + DeviceNames.pc(this));

        // the week, with the shown day last
        float[] hours = new float[7];
        String[] labels = new String[7];
        for (int i = 0; i < 7; i++) {
            LocalDate d = day.minusDays(6 - i);
            List<PcDays.App> dayApps = PcDays.apps(this, d);
            if (dayApps != null) for (PcDays.App a : dayApps) if (a.name.equals(name)) hours[i] = a.minutes / 60f;
            labels[i] = d.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)).substring(0, 1);
        }
        BarChartView chart = findViewById(R.id.chart);
        chart.setHighlightColor(getColor(DayTimelineView.color(cat)));
        chart.setData(hours, labels);

        final String current = cat;
        ListRow row = findViewById(R.id.row_cat);
        boolean group = "Other websites".equals(name) || "Windows".equals(name) || "Desktop".equals(name);
        row.setVisibility(group ? android.view.View.GONE : android.view.View.VISIBLE); // a group is not one thing to sort
        findViewById(R.id.cat_section).setVisibility(group ? android.view.View.GONE : android.view.View.VISIBLE);
        row.setIcon(PcCategories.icon(cat));
        row.setTitle(PcCategories.label(cat));
        row.setSubtitle(PcCategories.explain(cat));
        row.setOnClickListener(v -> chooseCategory(current));

        LinearLayout list = findViewById(R.id.sessions);
        list.removeAllViews();
        List<int[]> sessions = PcDays.sessions(this, day, name);
        for (int i = sessions.size() - 1; i >= 0; i--) {
            int[] s = sessions.get(i);
            ListRow r = new ListRow(this);
            r.setIcon(R.drawable.ic_today);
            r.setTitle(DayTimeline.clock(s[0]) + " – " + DayTimeline.clock(s[1]));
            r.setValue(Fmt.shortDuration((s[1] - s[0]) * 60_000L), false);
            r.setClickable(false);
            r.setBackground(null);
            list.addView(r);
        }
        ((TextView) findViewById(R.id.sessions_aside)).setText(sessions.isEmpty() ? "none recorded" : "latest first");
    }

    private void chooseCategory(String current) {
        Sheet.Option[] options = new Sheet.Option[PcCategories.ORDER.length];
        for (int i = 0; i < options.length; i++) {
            String c = PcCategories.ORDER[i];
            String label = PcCategories.label(c) + (c.equals(current) ? " · now" : "");
            options[i] = new Sheet.Option(PcCategories.icon(c), label, false, () -> {
                if (c.equals(current)) return;
                PcCategories.choose(this, name, c);
                android.widget.Toast.makeText(this, name + " counts as " + PcCategories.label(c).toLowerCase(Locale.ENGLISH)
                        + ". Your PC re-sorts the last two weeks within a few minutes.", android.widget.Toast.LENGTH_LONG).show();
                render();
            });
        }
        Sheet.choose(this, "What does " + name + " count as?", "Your focus forecast counts only focus.", options);
    }

    /** The same icon as its row: the program's or site's own, a drawn glyph, or its letter. */
    private void showIcon() {
        ImageView iv = findViewById(R.id.icon);
        float dp = getResources().getDisplayMetrics().density;
        Bitmap b = PcIcons.cached(this, name);
        int glyph = "Other websites".equals(name) ? R.drawable.ic_globe
                : ("Windows".equals(name) || "Desktop".equals(name)) ? R.drawable.ic_monitor : 0;
        if (b != null && glyph == 0) {
            iv.setImageBitmap(b);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(android.view.View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 16 * dp);
                }
            });
            return;
        }
        int pad = Math.round(15 * dp);
        iv.setBackgroundResource(R.drawable.icon_circle);
        iv.setPadding(pad, pad, pad, pad);
        if (glyph != 0) {
            iv.setImageResource(glyph);
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text)));
        } else {
            iv.setImageDrawable(new ListRow.LetterDrawable(name.substring(0, 1).toUpperCase(Locale.ROOT), getColor(R.color.text), 20 * dp));
        }
    }
}
