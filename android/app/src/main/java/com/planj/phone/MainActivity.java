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
        if (savedInstanceState == null) launchSequence();
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

        // The tab bar makes way for the keyboard, so a form gets the whole screen while typing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            View nav = findViewById(R.id.nav);
            View rule = findViewById(R.id.nav_rule);
            getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
                boolean typing = insets.isVisible(android.view.WindowInsets.Type.ime());
                nav.setVisibility(typing ? View.GONE : View.VISIBLE);
                rule.setVisibility(typing ? View.GONE : View.VISIBLE);
                return v.onApplyWindowInsets(insets);
            });
        }

        MoodReminder.ensureChannel(this);
        List<String> wanted = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!Agenda.allowed(this)) wanted.add(Manifest.permission.READ_CALENDAR);
        if (!wanted.isEmpty()) requestPermissions(wanted.toArray(new String[0]), 0);
    }

    /**
     * The entry: the splash icon grows and lifts away while the page rises up beneath it,
     * so opening the app feels like one continuous motion instead of a cut.
     */
    private void launchSequence() {
        View content = findViewById(R.id.tabs);
        View nav = findViewById(R.id.nav);
        float dp = getResources().getDisplayMetrics().density;
        content.setAlpha(0f);
        content.setTranslationY(48 * dp);
        nav.setAlpha(0f);
        Runnable rise = () -> {
            content.animate().alpha(1f).translationY(0f).setDuration(520).setStartDelay(60)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
            nav.animate().alpha(1f).setDuration(400).setStartDelay(220).start();
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSplashScreen().setOnExitAnimationListener(splash -> {
                View icon = splash.getIconView();
                if (icon != null) {
                    icon.animate().scaleX(1.25f).scaleY(1.25f).alpha(0f).setDuration(380)
                            .setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f)).start();
                }
                splash.animate().alpha(0f).translationY(-80 * dp).setDuration(420)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                        .withEndAction(splash::remove).start();
                rise.run();
            });
        } else {
            content.post(rise);
        }
    }

    private void select(View tab, View navItem) {
        boolean changed = tab.getVisibility() != View.VISIBLE;
        for (View v : new View[]{today.view(), odds.view(), journal.view(), accountTab.view(), settings.view()}) {
            if (v != tab) v.setVisibility(View.GONE);
        }
        for (View v : new View[]{navToday, navOdds, navJournal, navAccount, navSettings}) setNavSelected(v, false);
        tab.setVisibility(View.VISIBLE);
        setNavSelected(navItem, true);
        today.setLive(tab == today.view() && resumed);
        if (changed) { // a short rise so the new page arrives rather than blinks in
            tab.animate().cancel();
            tab.setAlpha(0f);
            tab.setTranslationY(10 * getResources().getDisplayMetrics().density);
            tab.animate().alpha(1f).translationY(0f).setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PLACES && Places.hasForeground(this)) {
            if (!Places.hasBackground(this)) askBackgroundLocation();
            else finishPlaces();
        }
        refresh(); // tomorrow's plans appear as soon as the calendar is allowed
    }

    private static final int REQ_PLACES = 42;
    private static final int REQ_PLACES_BG = 43;

    /** Settings > Places: explain, then ask; or offer pause and forget when already on. */
    void placesTapped() {
        if (Places.enabled(this) && Places.hasForeground(this) && Places.hasBackground(this)) {
            startActivity(new Intent(this, PlacesActivity.class));
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Turn on places?")
                .setMessage("Every 15 minutes planj notes which of your places you are at, like home, campus or the gym. "
                        + "The centre of each place stays on this phone and is never synced. Only \u201cplace 3 from 19:04\u201d is recorded.\n\n"
                        + "It needs location set to \u201cAllow all the time\u201d.")
                .setPositiveButton("Turn on", (d, w) -> {
                    Places.setEnabled(this, true);
                    if (!Places.hasForeground(this)) {
                        requestPermissions(Places.permissionsToAsk().toArray(new String[0]), REQ_PLACES);
                    } else if (!Places.hasBackground(this)) {
                        askBackgroundLocation();
                    } else {
                        finishPlaces();
                    }
                    refresh();
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void askBackgroundLocation() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("One more step")
                .setMessage("On the next screen choose \u201cAllow all the time\u201d, so places are noted while planj is closed.")
                .setPositiveButton("Continue", (d, w) -> requestPermissions(
                        new String[]{android.Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_PLACES_BG))
                .setNegativeButton("Later", null)
                .show();
    }

    /** Honor and others stop background work aggressively; ask to be left running. */
    private void finishPlaces() {
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
                // some phones hide this screen; places still work, just less often
            }
        }
        new Thread(() -> SnapshotJob.collectAndSync(this)).start();
    }

    private static void setNavSelected(View item, boolean selected) {
        ViewGroup g = (ViewGroup) item;
        for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).setSelected(selected);
    }

    void showAccount() {
        select(accountTab.view(), navAccount);
    }

    void showOdds() {
        select(odds.view(), navOdds);
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

    private boolean resumed;

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        today.setLive(false); // nothing polls while the app is in the background
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        today.setLive(today.view().getVisibility() == View.VISIBLE);
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
        // The hidden tabs can wait for the first frame; the visible one cannot.
        findViewById(R.id.tabs).post(() -> {
            odds.refresh();
            journal.refresh();
            accountTab.refresh();
            settings.refresh(granted);
        });
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
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == AccountTab.REQ_AVATAR) {
            if (!Avatar.save(this, data.getData())) toast("Could not read that image");
            refresh();
            return;
        }
        if (requestCode != REQ_EXPORT) return;
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
