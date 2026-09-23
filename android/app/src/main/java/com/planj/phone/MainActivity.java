package com.planj.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
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

public class MainActivity extends Activity {
    private static final int REQ_EXPORT = 1;
    private static final String[] MOOD_LABELS = {"Awful", "Bad", "Okay", "Good", "Great"};

    private final TextView[] moodButtons = new TextView[MOOD_LABELS.length];
    private TextView moodTitle, moodState, trackingDetail, syncDetail;
    private View dotTracking, dotSync;
    private EditText note;
    private Button grant, pair, reminder, export;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        moodTitle = findViewById(R.id.mood_title);
        moodState = findViewById(R.id.mood_state);
        trackingDetail = findViewById(R.id.tracking_detail);
        syncDetail = findViewById(R.id.sync_detail);
        dotTracking = findViewById(R.id.dot_tracking);
        dotSync = findViewById(R.id.dot_sync);
        note = findViewById(R.id.note);
        buildMoodRow(findViewById(R.id.mood_row));

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
    }

    private void buildMoodRow(LinearLayout row) {
        for (int i = 0; i < MOOD_LABELS.length; i++) {
            int mood = i + 1;
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);

            TextView circle = new TextView(this);
            circle.setText(String.valueOf(mood));
            circle.setGravity(Gravity.CENTER);
            circle.setTextColor(getColorStateList(R.color.mood_text));
            circle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            circle.setBackgroundResource(R.drawable.mood_circle);
            circle.setOnClickListener(v -> saveMood(mood, v));
            int size = dp(52);
            column.addView(circle, new LinearLayout.LayoutParams(size, size));

            TextView label = new TextView(this);
            label.setText(MOOD_LABELS[i]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setTextColor(getColor(R.color.muted));
            label.setPadding(0, dp(8), 0, 0);
            column.addView(label);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            row.addView(column, lp);
            moodButtons[i] = circle;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        tapped.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90)
                .withEndAction(() -> tapped.animate().scaleX(1f).scaleY(1f).setDuration(140).start()).start();
        LocalDate day = MoodStore.today();
        try {
            MoodStore.save(this, day, mood, note.getText().toString());
            note.setText("");
            note.clearFocus();
            syncInBackground();
        } catch (Exception e) {
            toast("Could not save: " + e.getMessage());
        }
        refresh();
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

    private void refresh() {
        LocalDate day = MoodStore.today();
        int logged = MoodStore.moodFor(this, day);
        moodTitle.setText("How was " + day.format(DateTimeFormatter.ofPattern("EEEE")) + "?");
        moodState.setText(logged == 0
                ? day.format(DateTimeFormatter.ofPattern("d MMMM")) + " · not logged yet"
                : day.format(DateTimeFormatter.ofPattern("d MMMM")) + " · logged " + logged + "/5");
        for (int i = 0; i < moodButtons.length; i++) {
            moodButtons[i].setSelected(logged == i + 1);
            moodButtons[i].setAlpha(logged == 0 || logged == i + 1 ? 1f : 0.55f);
        }

        boolean granted = hasUsageAccess();
        setDot(dotTracking, granted ? R.color.ok : R.color.warn);
        long last = UsageCollector.lastEventMs(this);
        trackingDetail.setText(!granted
                ? "Usage access is off — tap below to allow it"
                : UsageCollector.savedCount(this) + " events saved"
                + (last > 0 ? " · latest " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(last)) : ""));
        grant.setVisibility(granted ? View.GONE : View.VISIBLE);
        export.setEnabled(granted);

        String code = RelaySync.pairedCode(this);
        String error = RelaySync.lastError(this);
        long synced = RelaySync.lastSyncMs(this);
        setDot(dotSync, code == null ? R.color.idle : error != null ? R.color.warn : R.color.ok);
        pair.setText(code == null ? "Pair with PC" : "Paired · change code");
        if (code == null) {
            syncDetail.setText("Not paired — data stays on this phone");
        } else if (error != null) {
            syncDetail.setText("Retrying · " + error);
        } else {
            syncDetail.setText(synced == 0 ? "Paired · waiting for first sync"
                    : "Last sync " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(synced)));
        }

        reminder.setVisibility(MoodReminder.enabled(this) ? View.GONE : View.VISIBLE);
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
