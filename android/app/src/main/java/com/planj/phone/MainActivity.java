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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.io.OutputStream;
import java.time.LocalDate;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_EXPORT = 1;

    private TodayTab today;
    private OddsTab odds;
    private JournalTab journal;
    private SettingsTab settings;
    private AccountTab accountTab;
    private View navToday, navOdds, navJournal, navAccount, navSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ViewGroup tabs = findViewById(R.id.tabs);
        today = new TodayTab(this, tabs);
        odds = new OddsTab(this, tabs);
        journal = new JournalTab(this, tabs);
        accountTab = new AccountTab(this, tabs);
        settings = new SettingsTab(this, tabs);

        navToday = findViewById(R.id.nav_today);
        navOdds = findViewById(R.id.nav_odds);
        navJournal = findViewById(R.id.nav_journal);
        navAccount = findViewById(R.id.nav_account);
        navSettings = findViewById(R.id.nav_settings);
        navAccount.setOnClickListener(v -> select(accountTab.view(), navAccount));
        navToday.setOnClickListener(v -> select(today.view(), navToday));
        navOdds.setOnClickListener(v -> select(odds.view(), navOdds));
        navJournal.setOnClickListener(v -> select(journal.view(), navJournal));
        navSettings.setOnClickListener(v -> select(settings.view(), navSettings));
        select(today.view(), navToday);

        MoodReminder.ensureChannel(this);
        List<String> wanted = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!Agenda.allowed(this)) wanted.add(Manifest.permission.READ_CALENDAR);
        if (!wanted.isEmpty()) requestPermissions(wanted.toArray(new String[0]), 0);
    }

    private void select(View tab, View navItem) {
        for (View v : new View[]{today.view(), odds.view(), journal.view(), accountTab.view(), settings.view()}) v.setVisibility(View.GONE);
        for (View v : new View[]{navToday, navOdds, navJournal, navAccount, navSettings}) setNavSelected(v, false);
        tab.setVisibility(View.VISIBLE);
        setNavSelected(navItem, true);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refresh(); // tomorrow's plans appear as soon as the calendar is allowed
    }

    private static void setNavSelected(View item, boolean selected) {
        ViewGroup g = (ViewGroup) item;
        for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).setSelected(selected);
    }

    void showJournal(LocalDate day) {
        journal.show(day);
        select(journal.view(), navJournal);
    }

    void setPrivate(boolean on) {
        if (on == PrivateMode.isOn(this)) return;
        PrivateMode.set(this, on);
        toast(on ? "Private — nothing is recorded until you pinch out or tap the banner" : "Recording again");
        refresh();
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

    void refresh() {
        boolean granted = hasUsageAccess();
        today.refresh(granted);
        odds.refresh();
        journal.refresh();
        accountTab.refresh();
        settings.refresh(granted);
    }

    void syncInBackground() {
        new Thread(() -> {
            SnapshotJob.collectAndSync(this);
            runOnUiThread(this::refresh);
        }).start();
    }

    void saveEntry(LocalDate day, int mood, String note, List<String> tags) {
        try {
            MoodStore.save(this, day, mood, note, tags);
            syncInBackground();
        } catch (Exception e) {
            toast("Could not save: " + e.getMessage());
        }
        refresh();
    }

    boolean hasUsageAccess() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    void startExport() {
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

    void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }
}
