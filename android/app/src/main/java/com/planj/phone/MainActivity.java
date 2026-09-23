package com.planj.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.DateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_EXPORT = 1;
    private static final String[] MOOD_LABELS = {"Awful", "Bad", "Okay", "Good", "Great"};
    private static final int[] MOOD_COLORS = {0xFFF87171, 0xFFFB923C, 0xFFFBBF24, 0xFFA3E635, 0xFF4ADE80};
    private static final int CHART_DAYS = 7;

    private final TextView[] moodButtons = new TextView[MOOD_LABELS.length];
    private TextView headerDate, statScreen, statUnlocks, moodTitle, moodState, trackingDetail, syncDetail;
    private BarChartView chart;
    private View dotTracking, dotSync;
    private EditText note;
    private Button grant, pair, reminder, export, saveNote;
    private String savedNote = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        headerDate = findViewById(R.id.header_date);
        statScreen = findViewById(R.id.stat_screen);
        statUnlocks = findViewById(R.id.stat_unlocks);
        chart = findViewById(R.id.chart);
        moodTitle = findViewById(R.id.mood_title);
        moodState = findViewById(R.id.mood_state);
        trackingDetail = findViewById(R.id.tracking_detail);
        syncDetail = findViewById(R.id.sync_detail);
        dotTracking = findViewById(R.id.dot_tracking);
        dotSync = findViewById(R.id.dot_sync);
        note = findViewById(R.id.note);
        buildMoodRow(findViewById(R.id.mood_row));

        saveNote = findViewById(R.id.btn_save_note);
        saveNote.setOnClickListener(v -> saveNoteOnly());
        note.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateSaveNoteButton(); }
        });

        grant = findViewById(R.id.btn_grant);
        grant.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        pair = findViewById(R.id.btn_pair);
        pair.setOnClickListener(v -> askPairingCode());
        reminder = findViewById(R.id.btn_reminder);
        reminder.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        export = findViewById(R.id.btn_export);
        export.setOnClickListener(v -> startExport());

        MoodReminder.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        if (savedInstanceState == null) animateEntrance(findViewById(R.id.content));
    }

    /** Cards rise and fade in one after another on a fresh open. */
    private void animateEntrance(ViewGroup content) {
        float rise = dp(18);
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(rise);
            child.animate().alpha(1f).translationY(0f)
                    .setStartDelay(60L * i).setDuration(420)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void buildMoodRow(LinearLayout row) {
        for (int i = 0; i < MOOD_LABELS.length; i++) {
            int mood = i + 1;
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.setClipChildren(false);
            column.setClipToPadding(false);

            TextView circle = new TextView(this);
            circle.setText(String.valueOf(mood));
            circle.setGravity(Gravity.CENTER);
            circle.setTextColor(new ColorStateList(
                    new int[][]{{android.R.attr.state_selected}, {}},
                    new int[]{getColor(R.color.bg), getColor(R.color.muted)}));
            circle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            circle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            circle.setBackground(moodCircle(MOOD_COLORS[i]));
            circle.setOnClickListener(v -> saveMood(mood, v));
            int size = dp(56);
            column.addView(circle, new LinearLayout.LayoutParams(size, size));

            TextView label = new TextView(this);
            label.setText(MOOD_LABELS[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setTextColor(getColor(R.color.muted));
            label.setPadding(0, dp(8), 0, 0);
            column.addView(label);

            row.setClipChildren(false);
            row.addView(column, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            moodButtons[i] = circle;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Outlined when idle; when chosen, a solid disc of the mood's colour inside a translucent halo. */
    private Drawable moodCircle(int color) {
        GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.OVAL);
        halo.setColor((color & 0x00FFFFFF) | 0x40000000);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(color);
        int inset = dp(5);
        LayerDrawable selected = new LayerDrawable(new Drawable[]{halo, new InsetDrawable(disc, inset)});

        GradientDrawable idleDisc = new GradientDrawable();
        idleDisc.setShape(GradientDrawable.OVAL);
        idleDisc.setColor(getColor(R.color.surface_alt));
        idleDisc.setStroke(dp(1), getColor(R.color.border));
        Drawable idle = new InsetDrawable(idleDisc, inset);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, selected);
        states.addState(new int[]{}, idle);

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(getColor(R.color.ripple)), states, mask);
    }

    @Override
    protected void onResume() {
        super.onResume();
        MoodReminder.schedule(this);
        refresh();
        if (hasUsageAccess()) {
            SnapshotJob.schedule(this);
            syncInBackground();
        }
    }

    private void syncInBackground() {
        new Thread(() -> {
            SnapshotJob.collectAndSync(this);
            runOnUiThread(this::refresh);
        }).start();
    }

    private void saveMood(int mood, View tapped) {
        tapped.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        tapped.animate().scaleX(0.86f).scaleY(0.86f).setDuration(80)
                .withEndAction(() -> tapped.animate().scaleX(1f).scaleY(1f).setDuration(180)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.5f)).start()).start();
        persist(mood, note.getText().toString());
    }

    private void saveNoteOnly() {
        int mood = MoodStore.moodFor(this, MoodStore.today());
        if (mood == 0) {
            toast("Pick a mood first — the note is saved with it");
            return;
        }
        persist(mood, note.getText().toString());
        note.clearFocus();
    }

    private void persist(int mood, String text) {
        try {
            MoodStore.save(this, MoodStore.today(), mood, text);
            syncInBackground();
        } catch (Exception e) {
            toast("Could not save: " + e.getMessage());
        }
        refresh();
    }

    private void updateSaveNoteButton() {
        boolean changed = !note.getText().toString().trim().equals(savedNote);
        saveNote.setVisibility(changed ? View.VISIBLE : View.GONE);
    }

    private void refresh() {
        LocalDate day = MoodStore.today();
        headerDate.setText(LocalDate.now().format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)).toUpperCase(Locale.ENGLISH));

        MoodStore.Entry entry = MoodStore.entryFor(this, day);
        int logged = entry == null ? 0 : entry.mood;
        moodTitle.setText("How was " + day.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)) + "?");
        moodState.setText(logged == 0
                ? day.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)) + " · not logged yet"
                : MOOD_LABELS[logged - 1] + (entry.savedAtMs > 0
                        ? " · saved " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(entry.savedAtMs))
                        : " · saved") + " ✓");
        for (int i = 0; i < moodButtons.length; i++) {
            moodButtons[i].setSelected(logged == i + 1);
            moodButtons[i].setAlpha(logged == 0 || logged == i + 1 ? 1f : 0.5f);
        }
        savedNote = entry == null ? "" : entry.note;
        if (!note.hasFocus()) note.setText(savedNote); // never clobber something being typed
        updateSaveNoteButton();

        boolean granted = hasUsageAccess();
        setDot(dotTracking, granted ? R.color.ok : R.color.warn);
        long[] today = UsageCollector.todayStats(this);
        statScreen.setText(granted ? formatDuration(today[0]) : "—");
        statUnlocks.setText(granted ? String.valueOf(today[1]) : "—");
        trackingDetail.setText(granted ? "On" : "Usage access is off");
        grant.setVisibility(granted ? View.GONE : View.VISIBLE);
        export.setEnabled(granted);
        if (granted) fillChart();

        String code = RelaySync.pairedCode(this);
        String error = RelaySync.lastError(this);
        long synced = RelaySync.lastSyncMs(this);
        long confirmed = RelaySync.confirmedMs(this);
        pair.setText(code == null ? "Pair with PC" : "Change PC code");
        if (code == null) {
            setDot(dotSync, R.color.idle);
            syncDetail.setText("Not paired");
        } else if (error != null) {
            setDot(dotSync, R.color.warn);
            syncDetail.setText("Retrying · " + error);
        } else if (confirmed > 0) {
            setDot(dotSync, R.color.ok);
            syncDetail.setText("Confirmed " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(confirmed)));
        } else if (RelaySync.confirmationOverdue(this)) {
            setDot(dotSync, R.color.warn);
            syncDetail.setText("PC not answering — check code");
        } else {
            setDot(dotSync, R.color.idle);
            syncDetail.setText(synced == 0 ? "Waiting for first sync" : "Waiting for PC");
        }

        reminder.setVisibility(MoodReminder.enabled(this) ? View.GONE : View.VISIBLE);
    }

    private void fillChart() {
        float[] hours = new float[CHART_DAYS];
        String[] labels = new String[CHART_DAYS];
        LocalDate today = LocalDate.now();
        for (int i = 0; i < CHART_DAYS; i++) {
            LocalDate day = today.minusDays(CHART_DAYS - 1 - i);
            hours[i] = UsageCollector.dayStats(this, day)[0] / 3600000f;
            labels[i] = day.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)).substring(0, 1);
        }
        chart.setData(hours, labels);
    }

    private static String formatDuration(long ms) {
        long minutes = ms / 60000;
        return minutes < 60 ? minutes + "m" : (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    private void setDot(View dot, int colorRes) {
        dot.getBackground().mutate().setTint(getColor(colorRes));
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void askPairingCode() {
        EditText input = new EditText(this);
        input.setHint("XXXXX-XXXXX-XXXXX-XXXXX");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int pad = dp(24);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(pad, dp(8), pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Pair with PC")
                .setMessage("On your PC, open planj-tracker again to see its 20-character code, then type it here.")
                .setView(wrap)
                .setPositiveButton("Pair", (d, w) -> {
                    try {
                        RelaySync.pair(this, input.getText().toString());
                        toast("Paired — sending your history to the PC");
                        syncInBackground();
                    } catch (IllegalArgumentException e) {
                        toast(e.getMessage());
                    } catch (Exception e) {
                        toast("Could not pair: " + e.getMessage());
                    }
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, "planj-phone-" + LocalDate.now() + ".jsonl");
        startActivityForResult(intent, REQ_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                UsageCollector.collect(this);
                try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    UsageCollector.export(this, out);
                }
                toast("Exported");
            } catch (Exception e) {
                toast("Export failed: " + e.getMessage());
            }
            runOnUiThread(this::refresh);
        }).start();
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }
}
