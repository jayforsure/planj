package com.planj.phone;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class JournalTab {
    private final MainActivity a;
    private final View root;
    private final TextView summary, monthTitle, entryTitle, entryState, entryUsage;
    private final MonthView month;
    private final LinearLayout tags;
    private final EditText note;
    private MoodPicker picker;
    private final TextView[] chips = new TextView[MoodStore.TAGS.length];

    private YearMonth shown = YearMonth.now();
    private LocalDate selected = MoodStore.today();
    private int pickedMood;

    JournalTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_journal, container, false);
        container.addView(root);
        summary = root.findViewById(R.id.journal_summary);
        monthTitle = root.findViewById(R.id.month_title);
        month = root.findViewById(R.id.month);
        entryTitle = root.findViewById(R.id.entry_title);
        entryState = root.findViewById(R.id.entry_state);
        entryUsage = root.findViewById(R.id.entry_usage);
        tags = root.findViewById(R.id.tags);
        note = root.findViewById(R.id.entry_note);
        picker = new MoodPicker(a, root.findViewById(R.id.entry_mood_row), mood -> {
            pickedMood = mood;
            this.picker.select(mood);
        });

        root.findViewById(R.id.month_prev).setOnClickListener(v -> { shown = shown.minusMonths(1); refresh(); });
        root.findViewById(R.id.month_next).setOnClickListener(v -> {
            if (shown.isBefore(YearMonth.now())) { shown = shown.plusMonths(1); refresh(); }
        });
        root.findViewById(R.id.entry_save).setOnClickListener(v -> save());
        entryUsage.setOnClickListener(v -> a.startActivity(
                new Intent(a, DayDetailActivity.class).putExtra("day", selected.toString())));
        buildChips();
    }

    View view() {
        return root;
    }

    private void buildChips() {
        float density = a.getResources().getDisplayMetrics().density;
        for (int i = 0; i < MoodStore.TAGS.length; i++) {
            TextView chip = new TextView(a);
            String tag = MoodStore.TAGS[i];
            chip.setText(Character.toUpperCase(tag.charAt(0)) + tag.substring(1));
            chip.setTextSize(13);
            chip.setTextColor(a.getColorStateList(R.color.chip_text));
            chip.setBackgroundResource(R.drawable.chip_bg);
            chip.setPadding((int) (14 * density), (int) (8 * density), (int) (14 * density), (int) (8 * density));
            chip.setOnClickListener(v -> v.setSelected(!v.isSelected()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (8 * density);
            tags.addView(chip, lp);
            chips[i] = chip;
        }
    }

    void show(LocalDate day) {
        selected = day;
        shown = YearMonth.from(day);
        refresh();
    }

    private void save() {
        if (pickedMood == 0) {
            a.toast("Pick how the day felt first");
            return;
        }
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < chips.length; i++) if (chips[i].isSelected()) chosen.add(MoodStore.TAGS[i]);
        a.saveEntry(selected, pickedMood, note.getText().toString(), chosen);
        note.clearFocus();
    }

    void refresh() {
        Map<LocalDate, MoodStore.Entry> all = MoodStore.all(a);
        summary.setText(all.size() + (all.size() == 1 ? " day" : " days") + " logged");
        monthTitle.setText(shown.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)));
        month.set(shown, all, selected, day -> { selected = day; refresh(); });

        boolean today = selected.equals(MoodStore.today());
        entryTitle.setText(today ? "Today" : Fmt.longDate(selected));
        MoodStore.Entry e = all.get(selected);
        pickedMood = e == null ? 0 : e.mood;
        picker.select(pickedMood);
        entryState.setText(e == null ? (today ? "Not logged yet" : "Nothing written for this day")
                : MoodPicker.LABELS[e.mood - 1] + " · saved " + Fmt.clock(e.savedAtMs) + " ✓");
        if (!note.hasFocus()) note.setText(e == null ? "" : e.note);
        for (int i = 0; i < chips.length; i++) chips[i].setSelected(e != null && e.tags.contains(MoodStore.TAGS[i]));

        DayUsage usage = DayUsage.load(a, selected);
        long sleep = DayUsage.estimateSleepMs(a, selected);
        entryUsage.setText(usage.screenMs == 0 ? "" : Fmt.duration(usage.screenMs) + " on screen · " + usage.unlocks + " unlocks"
                + (sleep > 0 ? " · slept ≈ " + Fmt.shortDuration(sleep) : "") + "  ›");
    }
}
