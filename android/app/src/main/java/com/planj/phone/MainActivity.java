package com.planj.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
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

    private TextView moodTitle;
    private final Button[] moodButtons = new Button[5];
    private EditText note;
    private Button reminder;
    private TextView status;
    private Button grant;
    private Button export;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("planj");
        title.setTextSize(28);
        root.addView(title);

        moodTitle = new TextView(this);
        moodTitle.setTextSize(18);
        moodTitle.setPadding(0, pad, 0, pad / 2);
        root.addView(moodTitle);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 5; i++) {
            int mood = i + 1;
            Button b = new Button(this);
            b.setText(mood + "\n" + MOOD_LABELS[i]);
            b.setAllCaps(false);
            b.setOnClickListener(v -> saveMood(mood));
            row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            moodButtons[i] = b;
        }
        root.addView(row);

        note = new EditText(this);
        note.setHint("Note (optional) — e.g. lost on a trade, didn't prep");
        note.setTextSize(14);
        root.addView(note);

        reminder = new Button(this);
        reminder.setText("Turn on 21:30 reminder");
        reminder.setAllCaps(false);
        reminder.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        root.addView(reminder);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, pad, 0, pad / 2);
        root.addView(status);

        grant = new Button(this);
        grant.setText("Grant usage access");
        grant.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        root.addView(grant);

        export = new Button(this);
        export.setText("Export data");
        export.setOnClickListener(v -> startExport());
        root.addView(export);

        TextView privacy = new TextView(this);
        privacy.setText("Only which app is open, screen on/off, unlocks and your mood ratings are saved — never "
                + "notifications, messages, websites or what you type. Everything stays on this phone until you export it.");
        privacy.setTextSize(13);
        privacy.setPadding(0, pad, 0, 0);
        root.addView(privacy);

        setContentView(root);

        MoodReminder.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        MoodReminder.schedule(this);
        refresh();
        if (hasUsageAccess()) {
            SnapshotJob.schedule(this);
            new Thread(() -> {
                try {
                    UsageCollector.collect(this);
                } catch (Exception e) {
                    toast("Could not read usage: " + e.getMessage());
                }
                runOnUiThread(this::refresh);
            }).start();
        }
    }

    private void saveMood(int mood) {
        LocalDate day = MoodStore.today();
        try {
            MoodStore.save(this, day, mood, note.getText().toString());
            note.setText("");
            toast("Saved " + mood + "/5 for " + day.format(DateTimeFormatter.ofPattern("EEE d MMM")));
        } catch (Exception e) {
            toast("Could not save: " + e.getMessage());
        }
        refresh();
    }

    private void refresh() {
        LocalDate day = MoodStore.today();
        int logged = MoodStore.moodFor(this, day);
        moodTitle.setText("How was " + day.format(DateTimeFormatter.ofPattern("EEEE, d MMM")) + "?"
                + (logged > 0 ? "  — " + logged + "/5 ✓" : ""));
        for (int i = 0; i < 5; i++) {
            moodButtons[i].setAlpha(logged == 0 || logged == i + 1 ? 1f : 0.45f);
        }
        reminder.setVisibility(MoodReminder.enabled(this) ? Button.GONE : Button.VISIBLE);

        boolean granted = hasUsageAccess();
        grant.setVisibility(granted ? Button.GONE : Button.VISIBLE);
        export.setEnabled(granted);
        if (!granted) {
            status.setText("To track phone use, allow planj to read app usage.\nTap the button, find planj in the list and switch it on.");
            return;
        }
        long last = UsageCollector.lastEventMs(this);
        status.setText("Tracking is on · " + UsageCollector.savedCount(this) + " events saved"
                + (last > 0 ? "\nLatest: " + DateFormat.getDateTimeInstance().format(new Date(last)) : ""));
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
