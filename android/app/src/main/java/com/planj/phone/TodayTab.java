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
        headerDate.setText(shown.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)));
        int hour = LocalTime.now().getHour();
        greeting.setText(!isToday ? Fmt.shortDate(shown)
                : hour < 5 ? "Still up?" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening");
        appsTitle.setText(isToday ? "Today's apps" : "Apps that day");

        boolean priv = PrivateMode.isOn(a);
        privateBanner.setVisibility(priv ? View.VISIBLE : View.GONE);
        ((GestureScrollView) root).setPrivateLook(priv, false);

        if (granted) {
            if (!recall(shown) && statScreen.getText().length() == 0) {  // first load: a dash, never a blank card
                statScreen.setText("—");
                statUnlocks.setText("—");
                statQuiet.setText("—");
            }
            loadUsageAsync();
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

    private int loadGeneration;

    /** The week of usage is parsed off the main thread; a stale result is dropped if the day changed. */
    private void loadUsageAsync() {
        final int gen = ++loadGeneration;
        final LocalDate target = shown;
        new Thread(() -> {
            List<DayUsage> week = new ArrayList<>();
            Map<String, Long> weekTotals = new HashMap<>();
            for (int i = CHART_DAYS - 1; i >= 0; i--) {
                DayUsage u = DayUsage.load(a, target.minusDays(i));
                week.add(u);
                for (Map.Entry<String, Long> e : u.appMs.entrySet()) weekTotals.merge(e.getKey(), e.getValue(), Long::sum);
            }
            DayUsage.Quiet q = DayUsage.quiet(a, target);
            DayTimeline tl = DayTimeline.build(a, target);
            List<PcDays.App> pcApps = PcDays.apps(a, target);
            List<Map.Entry<String, Long>> ranked = new ArrayList<>(weekTotals.entrySet());
            ranked.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
            List<String> order = new ArrayList<>();
            for (int i = 0; i < Math.min(LEGEND_APPS, ranked.size()); i++) order.add(ranked.get(i).getKey());
            a.runOnUiThread(() -> {
                if (gen != loadGeneration) return;
                DayUsage day = week.get(week.size() - 1);
                statScreen.setText(Fmt.shortDuration(day.screenMs));
                statUnlocks.setText(String.valueOf(day.unlocks));
                statQuiet.setText(q == null ? "—" : Fmt.shortDuration(q.ms()));
                statQuietLabel.setText(q != null && q.charging ? "Device-free ⚡" : "Device-free");
                LocalDate first = target.minusDays(CHART_DAYS - 1);
                String range = first.getMonth() == target.getMonth()
                        ? first.getDayOfMonth() + " – " + target.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
                        : first.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) + " – " + target.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
                weekRange.setText(range);
                chart.setData(week, order, this::openDay);
                fillLegend(order);
                AppRows.fill(a, topApps, day, 4);
                showTimeline(tl);
                showPcApps(pcApps);
                remember(target, day.screenMs, day.unlocks, q == null ? -1 : q.ms(), q != null && q.charging);
            });
        }).start();
    }

    /** The last numbers shown for a day, so reopening the app shows them at once. */
    private void remember(LocalDate d, long screenMs, int unlocks, long quietMs, boolean charged) {
        a.getSharedPreferences("planj_today", android.content.Context.MODE_PRIVATE).edit()
                .putString("day", d.toString()).putLong("screen", screenMs).putInt("unlocks", unlocks)
                .putLong("quiet", quietMs).putBoolean("charged", charged).apply();
    }

    private boolean recall(LocalDate d) {
        android.content.SharedPreferences p = a.getSharedPreferences("planj_today", android.content.Context.MODE_PRIVATE);
        if (!d.toString().equals(p.getString("day", null))) return false;
        statScreen.setText(Fmt.shortDuration(p.getLong("screen", 0)));
        statUnlocks.setText(String.valueOf(p.getInt("unlocks", 0)));
        long quiet = p.getLong("quiet", -1);
        statQuiet.setText(quiet < 0 ? "—" : Fmt.shortDuration(quiet));
        statQuietLabel.setText(p.getBoolean("charged", false) ? "Device-free ⚡" : "Device-free");
        return true;
    }

    /** "On your PC": apps and recognised sites, with a bar in the category's colour. */
    private void showPcApps(List<PcDays.App> apps) {
        View section = root.findViewById(R.id.pc_section), card = root.findViewById(R.id.pc_card);
        boolean has = apps != null && !apps.isEmpty();
        section.setVisibility(has ? View.VISIBLE : View.GONE);
        card.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!has) return;
        int total = 0;
        for (PcDays.App x : apps) total += x.minutes;
        ((TextView) root.findViewById(R.id.pc_total)).setText(Fmt.shortDuration(total * 60_000L));
        LinearLayout list = root.findViewById(R.id.pc_apps);
        list.removeAllViews();
        float dp = a.getResources().getDisplayMetrics().density;
        int max = apps.get(0).minutes;
        for (int i = 0; i < Math.min(6, apps.size()); i++) {
            PcDays.App x = apps.get(i);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, Math.round(10 * dp), 0, Math.round(10 * dp));

            TextView badge = new TextView(a); // the first letter on the category's colour
            badge.setText(x.name.substring(0, 1).toUpperCase(Locale.ENGLISH));
            badge.setGravity(android.view.Gravity.CENTER);
            badge.setTextColor(a.getColor(R.color.bg));
            badge.setTextSize(15);
            badge.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(10 * dp);
            bg.setColor(a.getColor(DayTimelineView.color(x.cat)));
            badge.setBackground(bg);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Math.round(36 * dp), Math.round(36 * dp));
            bp.setMarginEnd(Math.round(16 * dp));
            row.addView(badge, bp);

            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout top = new LinearLayout(a);
            top.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = new TextView(a);
            name.setText(x.name);
            name.setTextColor(a.getColor(R.color.text));
            name.setTextSize(16);
            top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView time = new TextView(a);
            time.setText(Fmt.shortDuration(x.minutes * 60_000L) + " · " + label(x.cat));
            time.setTextColor(a.getColor(R.color.muted));
            time.setTextSize(13);
            top.addView(time);
            col.addView(top);
            android.widget.FrameLayout track = new android.widget.FrameLayout(a);
            GradientDrawable tb = new GradientDrawable();
            tb.setCornerRadius(3 * dp);
            tb.setColor(a.getColor(R.color.surface_alt));
            track.setBackground(tb);
            View fill = new View(a);
            GradientDrawable fb = new GradientDrawable();
            fb.setCornerRadius(3 * dp);
            fb.setColor(a.getColor(DayTimelineView.color(x.cat)));
            fill.setBackground(fb);
            track.addView(fill, new android.widget.FrameLayout.LayoutParams(0, Math.round(4 * dp)));
            final float share = (float) x.minutes / Math.max(1, max);
            track.addOnLayoutChangeListener((v, l, t, rr, b, ol, ot, orr, ob) -> {
                int w = Math.max(Math.round(4 * dp), Math.round((rr - l) * share));
                if (fill.getLayoutParams().width != w) {
                    fill.getLayoutParams().width = w;
                    fill.requestLayout();
                }
            });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(4 * dp));
            tp.topMargin = Math.round(8 * dp);
            col.addView(track, tp);
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            list.addView(row);
        }
    }

    private static String label(String cat) {
        switch (cat) {
            case "focus": return "focus";
            case "entertainment": return "watching";
            case "social": return "social";
            case "chat": return "chat";
            default: return "other";
        }
    }

    private void showTimeline(DayTimeline tl) {
        ((DayTimelineView) root.findViewById(R.id.timeline)).setData(tl);
        String where = tl.whereLine();
        TextView w = root.findViewById(R.id.timeline_where);
        w.setText(where.isEmpty() ? (Places.enabled(a) ? "No places yet that day" : "Places are off") : where);
        TextView pc = root.findViewById(R.id.timeline_pc);
        String line = tl.pcLine();
        pc.setText(line == null ? "PC · not reported for this day" : line);
        ((TextView) root.findViewById(R.id.day_title)).setText(tl.day.equals(LocalDate.now()) ? "Your day" : "That day");
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
