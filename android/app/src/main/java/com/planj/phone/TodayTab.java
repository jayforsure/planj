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
    private final ViewGroup content;
    private final TextView headerDate, greeting, statScreen, statUnlocks, statQuiet, statQuietLabel,
            weekRange, appsTitle, moodTitle, moodState, moodNote, privateBanner;
    private final StackedBarChartView chart;
    private final LinearLayout legend, topApps;
    private final MoodPicker picker;
    private LocalDate shown = LocalDate.now();

    TodayTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_today, container, false);
        container.addView(root);
        content = root.findViewById(R.id.content);
        headerDate = root.findViewById(R.id.header_date);
        greeting = root.findViewById(R.id.greeting);
        statScreen = root.findViewById(R.id.stat_screen);
        statUnlocks = root.findViewById(R.id.stat_unlocks);
        statQuiet = root.findViewById(R.id.stat_quiet);
        statQuietLabel = root.findViewById(R.id.stat_quiet_label);
        weekRange = root.findViewById(R.id.week_range);
        appsTitle = root.findViewById(R.id.apps_title);
        chart = root.findViewById(R.id.chart);
        legend = root.findViewById(R.id.legend);
        topApps = root.findViewById(R.id.top_apps);
        moodTitle = root.findViewById(R.id.mood_title);
        moodState = root.findViewById(R.id.mood_state);
        moodNote = root.findViewById(R.id.mood_note);
        privateBanner = root.findViewById(R.id.private_banner);
        picker = new MoodPicker(a, root.findViewById(R.id.mood_row), mood -> {
            MoodStore.Entry e = MoodStore.entryFor(a, moodDay());
            a.saveEntry(moodDay(), mood, e == null ? "" : e.note, e == null ? List.of() : e.tags);
        });
        root.findViewById(R.id.see_all).setOnClickListener(v -> openDay(shown));
        root.findViewById(R.id.me).setOnClickListener(v -> a.showAccount());
        root.findViewById(R.id.chip_mood).setOnClickListener(v -> a.showJournal(shown));
        root.findViewById(R.id.chip_private).setOnClickListener(v -> a.setPrivate(!PrivateMode.isOn(a)));
        moodNote.setOnClickListener(v -> a.showJournal(moodDay()));
        privateBanner.setOnClickListener(v -> a.setPrivate(false));

        ((GestureScrollView) root).setGestureListener(new GestureScrollView.Listener() {
            @Override
            public void onSwipe(int direction) {
                LocalDate next = shown.plusDays(direction); // swipe left (-1) = the day before
                if (next.isAfter(LocalDate.now())) return;
                shown = next;
                refresh(a.hasUsageAccess());
            }

            @Override
            public void onPinch(boolean in) {
                a.setPrivate(in);
            }
        });
        animateEntrance(content);
    }

    View view() {
        return root;
    }

    private LocalDate moodDay() {
        return shown.equals(LocalDate.now()) ? MoodStore.today() : shown;
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
        boolean isToday = shown.equals(LocalDate.now());
        Avatar.show(a, root.findViewById(R.id.me_photo), root.findViewById(R.id.me_initial));
        ((android.widget.ImageView) root.findViewById(R.id.chip_private)).setImageTintList(android.content.res.ColorStateList.valueOf(a.getColor(PrivateMode.isOn(a) ? R.color.accent : R.color.text)));
        headerDate.setText(shown.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)));
        int hour = LocalTime.now().getHour();
        greeting.setText(!isToday ? Fmt.shortDate(shown)
                : hour < 5 ? "Still up?" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening");
        appsTitle.setText(isToday ? "Today's apps" : "Apps that day");

        boolean priv = PrivateMode.isOn(a);
        privateBanner.setVisibility(priv ? View.VISIBLE : View.GONE);
        ((GestureScrollView) root).setPrivateLook(priv, false);

        if (granted) {
            List<DayUsage> week = new ArrayList<>();
            Map<String, Long> weekTotals = new HashMap<>();
            for (int i = CHART_DAYS - 1; i >= 0; i--) {
                DayUsage u = DayUsage.load(a, shown.minusDays(i));
                week.add(u);
                for (Map.Entry<String, Long> e : u.appMs.entrySet()) weekTotals.merge(e.getKey(), e.getValue(), Long::sum);
            }
            DayUsage day = week.get(week.size() - 1);
            statScreen.setText(Fmt.shortDuration(day.screenMs));
            statUnlocks.setText(String.valueOf(day.unlocks));
            DayUsage.Quiet q = DayUsage.quiet(a, shown);
            statQuiet.setText(q == null ? "—" : Fmt.shortDuration(q.ms()));
            statQuietLabel.setText(q != null && q.charging ? "Device-free ⚡" : "Device-free");

            LocalDate first = shown.minusDays(CHART_DAYS - 1);
            String range = first.getMonth() == shown.getMonth()
                    ? first.getDayOfMonth() + " – " + shown.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
                    : first.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) + " – " + shown.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
            weekRange.setText(range);

            List<Map.Entry<String, Long>> ranked = new ArrayList<>(weekTotals.entrySet());
            ranked.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
            List<String> order = new ArrayList<>();
            for (int i = 0; i < Math.min(LEGEND_APPS, ranked.size()); i++) order.add(ranked.get(i).getKey());
            chart.setData(week, order, this::openDay);
            fillLegend(order);
            AppRows.fill(a, topApps, day, 4);
        } else {
            statScreen.setText("—");
            statUnlocks.setText("—");
            statQuiet.setText("—");
        }

        LocalDate md = moodDay();
        MoodStore.Entry entry = MoodStore.entryFor(a, md);
        moodTitle.setText("How was " + (isToday ? md.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)) : "that day") + "?");
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
