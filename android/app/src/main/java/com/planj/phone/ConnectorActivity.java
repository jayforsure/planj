package com.planj.phone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;

/**
 * One connector's page: what it is, whether it's connected, what it adds to your odds and what
 * checks them, then its own settings. TAR UMT has its own page for its sign-in.
 */
public class ConnectorActivity extends Activity {
    static final String EXTRA_ID = "id";
    private static final int REQ_CALENDAR = 41;
    private PageBuilder page;
    private Connectors.Connector c;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_page);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        page = new PageBuilder(this, findViewById(R.id.stage));
        c = Connectors.get(getIntent().getStringExtra(EXTRA_ID));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        boolean on = c.connected(this);
        page.clear();
        page.icon(c.picture(this), c.glyph);
        page.title(c.name(this));
        page.blurb(on ? c.status(this) : c.about + ".");
        if (on) {
            LinearLayout odds = new LinearLayout(this);
            odds.setOrientation(LinearLayout.VERTICAL);
            page.stage.addView(odds);
            ConnectorOdds.fillAsync(this, odds, c.id, c.checkedAgainst);
        } else {
            page.note(ConnectorOdds.summary(c.id) + " to your odds once connected.");
        }
        switch (c.id) {
            case "phone": phone(on); break;
            case "pc": pc(on); break;
            case "places": places(on); break;
            default: calendar(on); break;
        }
    }

    private void phone(boolean on) {
        page.section("Settings");
        page.link(R.drawable.ic_apps, "Usage access", on ? "On" : "Off · planj needs it to see your phone",
                () -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        page.link(R.drawable.ic_lock, "Private apps", "Recorded only as “Private”",
                () -> startActivity(new Intent(this, AppPickerActivity.class)));
        page.link(R.drawable.ic_edit, "Name", DeviceNames.phone(this), this::devices);
    }

    private void pc(boolean on) {
        if (!on) {
            page.note("Install planj's tracker on your PC and sign in there with the same account. It shows here once it reports.");
            return;
        }
        page.section("Settings");
        page.link(R.drawable.ic_edit, "Name", DeviceNames.pc(this), this::devices);
    }

    private void places(boolean on) {
        if (!on) {
            page.primary("Connect", () -> startActivity(new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_CONNECT, "places")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
            return;
        }
        page.section("Settings");
        page.link(R.drawable.ic_place, "Your places", Places.count(this) + " marked or noticed",
                () -> startActivity(new Intent(this, PlacesActivity.class)));
        page.secondary("Disconnect", () -> Sheet.confirm(this, R.drawable.ic_place, "Disconnect Places?",
                "planj stops noting where you are. Your places are kept, so you can connect again later.",
                "Disconnect", true, () -> {
                    Places.setEnabled(this, false);
                    new Thread(() -> ArrivalWatch.arm(this)).start();
                    render();
                }));
    }

    private void calendar(boolean on) {
        if (on) {
            page.secondary("Disconnect", () -> Sheet.confirm(this, R.drawable.ic_journal, "Disconnect Calendar?",
                    "planj stops reading your calendars. Android's own permission can be removed in app settings.",
                    "Disconnect", true, () -> {
                        Agenda.setOn(this, false);
                        render();
                    }));
            return;
        }
        page.primary("Connect", () -> {
            Agenda.setOn(this, true);
            if (checkSelfPermission(Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                render();
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)
                    || !getSharedPreferences("planj_connectors", MODE_PRIVATE).getBoolean("calendar_asked", false)) {
                getSharedPreferences("planj_connectors", MODE_PRIVATE).edit().putBoolean("calendar_asked", true).apply();
                requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQ_CALENDAR);
            } else { // Android won't ask again: the switch is in app settings
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getPackageName())));
            }
        });
    }

    private void devices() {
        startActivity(new Intent(this, AccountActivity.class)
                .putExtra(AccountActivity.EXTRA_SCREEN, AccountStore.signedIn(this) ? "devices" : "welcome"));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        render();
    }
}
