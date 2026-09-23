package com.planj.phone;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TodayTab {
    private static final int CHART_DAYS = 7;
    private static final int LEGEND_APPS = 5;

    private final MainActivity a;
    private final View root;
    private final TextView headerDate, greeting, statScreen, statUnlocks, statSleep, moodTitle, moodState, moodNote;
    private final StackedBarChartView chart;
    private final LinearLayout legend, topApps;
    private final MoodPicker picker;

    TodayTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_today, container, false);
        container.addView(root);
        headerDate = root.findViewById(R.id.header_date);
        greeting = root.findViewById(R.id.greeting);
        statScreen = root.findViewById(R.id.stat_screen);
        statUnlocks = root.findViewById(R.id.stat_unlocks);
        statSleep = root.findViewById(R.id.stat_sleep);
        chart = root.findViewById(R.id.chart);
        legend = root.findViewById(R.id.legend);
        topApps = root.findViewById(R.id.top_apps);
        moodTitle = root.findViewById(R.id.mood_title);
        moodState = root.findViewById(R.id.mood_state);
        moodNote = root.findViewById(R.id.mood_note);
        picker = new MoodPicker(a, root.findViewById(R.id.mood_row), mood -> {
            MoodStore.Entry e = MoodStore.entryFor(a, MoodStore.today());
            a.saveEntry(MoodStore.today(), mood, e == null ? "" : e.note, e == null ? List.of() : e.tags);
        });
        root.findViewById(R.id.see_all).setOnClickListener(v -> openDay(LocalDate.now()));
        moodNote.setOnClickListener(v -> a.showJournal(MoodStore.today()));
        animateEntrance(root.findViewById(R.id.content));
    }

    View view() {
        return root;
    }

    private void animateEntrance(ViewGroup content) {
        float rise = 18 * a.getResources().getDisplayMetrics().density;
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(rise);
            child.animate().alpha(1f).translationY(0f).setStartDelay(50L * i).setDuration(420)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void openDay(LocalDate day) {
        a.startActivity(new Intent(a, DayDetailActivity.class).putExtra("day", day.toString()));
    }

    void refresh(boolean granted) {
        LocalDate today = LocalDate.now();
        headerDate.setText(today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)).toUpperCase(Locale.ENGLISH));
        int hour = LocalTime.now().getHour();
        greeting.setText(hour < 5 ? "Still up?" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening");

        if (granted) {
            List<DayUsage> week = new ArrayList<>();
            Map<String, Long> weekTotals = new HashMap<>();
            for (int i = CHART_DAYS - 1; i >= 0; i--) {
                DayUsage u = DayUsage.load(a, today.minusDays(i));
                week.add(u);
                for (Map.Entry<String, Long> e : u.appMs.entrySet()) weekTotals.merge(e.getKey(), e.getValue(), Long::sum);
            }
            DayUsage now = week.get(week.size() - 1);
            statScreen.setText(Fmt.shortDuration(now.screenMs));
            statUnlocks.setText(String.valueOf(now.unlocks));
            long sleep = DayUsage.estimateSleepMs(a, today);
            statSleep.setText(sleep > 0 ? Fmt.shortDuration(sleep) : "—");

            List<Map.Entry<String, Long>> ranked = new ArrayList<>(weekTotals.entrySet());
            ranked.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
            List<String> order = new ArrayList<>();
            for (int i = 0; i < Math.min(LEGEND_APPS, ranked.size()); i++) order.add(ranked.get(i).getKey());
            chart.setData(week, order, this::openDay);
            fillLegend(order);
            AppRows.fill(a, topApps, now, 4);
        } else {
            statScreen.setText("—");
            statUnlocks.setText("—");
            statSleep.setText("—");
        }

        LocalDate day = MoodStore.today();
        MoodStore.Entry entry = MoodStore.entryFor(a, day);
        moodTitle.setText("How was " + day.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)) + "?");
        if (entry == null) {
            moodState.setText("Not logged yet");
            moodNote.setText("Add a note in Journal ›");
            picker.select(0);
        } else {
            moodState.setText(MoodPicker.LABELS[entry.mood - 1] + " · saved " + Fmt.clock(entry.savedAtMs) + " ✓");
            moodNote.setText(entry.note.isEmpty() ? "Add a note in Journal ›" : "“" + entry.note + "”");
            picker.select(entry.mood);
        }
    }

    private void fillLegend(List<String> order) {
        legend.removeAllViews();
        float density = a.getResources().getDisplayMetrics().density;
        for (String pkg : order) {
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(android.view.Gravity.CENTER_VERTICAL);
            item.setPadding(0, 0, (int) (12 * density), 0);
            View dot = new View(a);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(AppPalette.color(pkg));
            dot.setBackground(shape);
            int size = (int) (8 * density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = (int) (5 * density);
            item.addView(dot, lp);
            TextView name = new TextView(a);
            name.setText(AppPalette.label(a, pkg));
            name.setTextColor(a.getColor(R.color.muted));
            name.setTextSize(11);
            name.setMaxLines(1);
            item.addView(name);
            legend.addView(item);
        }
    }
}
