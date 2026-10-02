package com.planj.phone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

/** The Calendar connector: your phone's calendars, Google Calendar included when it syncs here. */
public class CalendarActivity extends Activity {
    private static final int REQ = 41;
    private PageBuilder page;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_page);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        page = new PageBuilder(this, findViewById(R.id.stage));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        boolean on = Agenda.allowed(this);
        page.clear();
        page.icon(null, R.drawable.ic_journal);
        page.title("Calendar");
        page.blurb(on ? "Connected" : "Your phone's calendars, including Google Calendar when it syncs to this phone. "
                + "Tomorrow's plans show in Odds, so forecasts know what your day holds.");
        if (on) {
            page.note("Tomorrow's plans show in Odds. Events are read on this phone and never synced.");
            page.secondary("Disconnect", () -> Sheet.confirm(this, R.drawable.ic_journal, "Disconnect Calendar?",
                    "planj stops reading your calendars. Android's own permission can be removed in app settings.",
                    "Disconnect", true, () -> {
                        Agenda.setOn(this, false);
                        render();
                    }));
        } else {
            page.note("Events are read on this phone and never synced.");
            page.primary("Connect", this::connect);
        }
    }

    private void connect() {
        Agenda.setOn(this, true);
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            render();
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR) || !getSharedPreferences("planj_connectors", MODE_PRIVATE).getBoolean("calendar_asked", false)) {
            getSharedPreferences("planj_connectors", MODE_PRIVATE).edit().putBoolean("calendar_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQ);
        } else { // Android won't ask again: the switch is in app settings
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getPackageName())));
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        render();
    }
}
