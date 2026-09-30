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

    // Live: while Today is on screen, the PC's "now" every 3 s and the phone's own numbers every 30 s.
    private static final long LIVE_EVERY_MS = 3_000;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable liveTick = this::pollLive;
    private boolean live, fetching;
    private int liveTicks;
    private String liveAppsKey;
    private PcLive lastLive;

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
        appsTitle.setText(DeviceNames.phone(a));
        showPhoneNow();

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
                ((TextView) root.findViewById(R.id.apps_total)).setText(Fmt.shortDuration(day.appTotalMs()));
                showTimeline(tl);
                showPcApps(pcApps, target);
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

    /** Starts or stops live updates; on only while Today is the visible tab and the app is open. */
    void setLive(boolean on) {
        if (on == live) return;
        live = on;
        ui.removeCallbacks(liveTick);
        if (on) ui.post(liveTick);
        showPhoneNow();
    }

    private void pollLive() {
        if (!live) return;
        ui.postDelayed(liveTick, LIVE_EVERY_MS);
        if (fetching || !shown.equals(LocalDate.now())) return;
        fetching = true;
        final boolean phoneToo = ++liveTicks % 10 == 1; // on the first tick, then every 30 s
        new Thread(() -> {
            PcLive l = PcLive.fetch(a);
            if (phoneToo && !PrivateMode.isOn(a)) {
                try {
                    UsageCollector.collect(a);
                } catch (Exception ignored) {
                    // the next tick tries again
                }
                PhoneLive.publish(a, a.getPackageName(), a.resumedAtMs()); // planj is in front: you are looking at it
            }
            a.runOnUiThread(() -> {
                fetching = false;
                if (!live) return;
                showLive(l);
                showPhoneNow();
                if (phoneToo && liveTicks > 1) refresh(a.hasUsageAccess());
            });
        }).start();
    }

    /** The live row at the top of "On your PC", and the day's totals as the PC has them now. */
    private void showLive(PcLive l) {
        ListRow now = root.findViewById(R.id.pc_now);
        if (l == null || !l.fresh() || !shown.equals(LocalDate.now())) {
            now.setVisibility(View.GONE);
            lastLive = null;
            return;
        }
        lastLive = l;
        now.setVisibility(View.VISIBLE);
        String since = Fmt.clock(l.sinceMs);
        if (l.locked) {
            now.setTag(null);
            now.setIcon(R.drawable.ic_lock);
            now.setTitle("Locked");
            now.setSubtitle("Since " + since);
            now.setValue("", false);
        } else if (l.idle) {
            now.setTag(null);
            now.setIcon(R.drawable.ic_monitor);
            now.setTitle("Away");
            now.setSubtitle("Since " + since + " · last on " + l.name);
            now.setValue("", false);
        } else {
            PcAppRows.bindIcon(a, now, l.name);
            now.setTitle(l.name);
            long ms = System.currentTimeMillis() - l.sinceMs;
            now.setSubtitle(ms < 60_000 ? "Just now" : Fmt.shortDuration(ms) + " so far");
            now.setValue("● Now", false);
            now.setValueColor(a.getColor(R.color.accent));
        }
        StringBuilder key = new StringBuilder();
        for (PcDays.App x : l.apps) key.append(x.name).append(x.minutes).append(',');
        if (!key.toString().equals(liveAppsKey)) {
            liveAppsKey = key.toString();
            showPcApps(l.apps, shown);
        } else {
            showSection(true);
        }
    }

    /**
     * The phone's own "now": while you look at Today that is planj itself, shown all the same,
     * so both devices read the same way.
     */
    private void showPhoneNow() {
        ListRow now = root.findViewById(R.id.phone_now);
        if (!live || !shown.equals(LocalDate.now())) {
            now.setVisibility(View.GONE);
            return;
        }
        now.setVisibility(View.VISIBLE);
        String pkg = a.getPackageName();
        if (now.getTag() == null) {
            android.graphics.drawable.Drawable d = AppPalette.icon(a, pkg);
            if (d != null) now.setImage(d);
            else now.setLetter(AppPalette.label(a, pkg));
            now.setTitle(AppPalette.label(a, pkg));
            now.setValue("● Now", false);
            now.setValueColor(a.getColor(R.color.accent));
            now.setTag(pkg);
        }
        long ms = System.currentTimeMillis() - a.resumedAtMs();
        now.setSubtitle(ms < 60_000 ? "Just now" : Fmt.shortDuration(ms) + " so far");
    }

    private void showSection(boolean has) {
        root.findViewById(R.id.pc_section).setVisibility(has ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.pc_card).setVisibility(has ? View.VISIBLE : View.GONE);
    }

    /** "On your PC": apps and recognised sites, with a bar in the category's colour. */
    private void showPcApps(List<PcDays.App> apps, LocalDate day) {
        View section = root.findViewById(R.id.pc_section), card = root.findViewById(R.id.pc_card);
        boolean live = lastLive != null && lastLive.fresh() && day.equals(LocalDate.now());
        boolean has = (apps != null && !apps.isEmpty()) || live;
        if (!live) root.findViewById(R.id.pc_now).setVisibility(View.GONE); // only today has a "now"
        section.setVisibility(has ? View.VISIBLE : View.GONE);
        card.setVisibility(has ? View.VISIBLE : View.GONE);
        ((TextView) root.findViewById(R.id.pc_title)).setText(DeviceNames.pc(a));
        if (!has) return;
        int total = 0;
        if (apps != null) for (PcDays.App x : apps) total += x.minutes;
        ((TextView) root.findViewById(R.id.pc_total)).setText(Fmt.shortDuration(total * 60_000L));
        PcAppRows.fill(a, root.findViewById(R.id.pc_apps), apps, 5);
        root.findViewById(R.id.pc_see_all).setOnClickListener(v -> a.startActivity(
                new Intent(a, PcAppsActivity.class).putExtra(PcAppsActivity.EXTRA_DAY, day.toString())));
    }

    private void showTimeline(DayTimeline tl) {
        ((DayTimelineView) root.findViewById(R.id.timeline)).setData(tl);
        String where = tl.whereLine();
        TextView w = root.findViewById(R.id.timeline_where);
        w.setText(where.isEmpty() ? (Places.enabled(a) ? "No places yet that day" : "Places are off") : where);
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
