package com.planj.phone;

import android.app.AlertDialog;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

final class SettingsTab {
    private final MainActivity a;
    private final View root;
    private final ListRow tracking, priv, reminder, places;

    SettingsTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_settings, container, false);
        container.addView(root);
        tracking = root.findViewById(R.id.row_tracking);
        priv = root.findViewById(R.id.row_private);
        reminder = root.findViewById(R.id.row_reminder);
        places = root.findViewById(R.id.row_places);
        places.setOnClickListener(v -> a.placesTapped());

        tracking.setOnClickListener(v -> a.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        priv.setOnClickListener(v -> a.setPrivate(!PrivateMode.isOn(a)));
        root.findViewById(R.id.row_private_apps).setOnClickListener(v -> a.startActivity(new Intent(a, AppPickerActivity.class)));
        reminder.setOnClickListener(v -> a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName())));
        root.findViewById(R.id.row_export).setOnClickListener(v -> a.startExport());
        root.findViewById(R.id.row_privacy).setOnClickListener(v -> new AlertDialog.Builder(a)
                .setTitle("How privacy works")
                .setMessage("Saved on this phone: which app is open, screen on/off, unlocks and your journal. Never notifications, messages, websites or what you type.\n\n"
                        + "Private mode pauses everything, from here, the Quick Settings tile, or by pinching in on Today. Money, password, health and dating apps are recorded only as “Private” from the start.\n\n"
                        + "Places, if you turn them on, are kept as numbers: each place's centre stays on this phone and is never synced. "
                        + "The map and place search use OpenStreetMap; only what you type and the part of the map you look at are sent.\n\n"
                        + "Syncing to your PC goes through a relay that only ever holds ciphertext; the key is derived on your devices from your password.")
                .setPositiveButton("Got it", null).show());

        ListRow tile = root.findViewById(R.id.row_tile);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            tile.setOnClickListener(v -> a.getSystemService(android.app.StatusBarManager.class).requestAddTileService(
                    new android.content.ComponentName(a, PrivateTileService.class), "planj Private",
                    android.graphics.drawable.Icon.createWithResource(a, R.drawable.ic_private),
                    a.getMainExecutor(), result -> {}));
        } else {
            tile.setOnClickListener(v -> a.toast("Swipe down, tap the pencil, and drag “planj Private” into your tiles"));
        }

        TextView version = root.findViewById(R.id.version);
        try {
            version.setText("planj " + a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName);
        } catch (Exception e) {
            version.setText("planj");
        }
    }

    View view() {
        return root;
    }

    void refresh(boolean granted) {
        tracking.setSubtitle(granted ? "On" : "Off");
        tracking.setTint(a.getColor(granted ? R.color.text : R.color.warn));
        boolean p = PrivateMode.isOn(a);
        priv.setSubtitle(p ? "On since " + Fmt.clock(PrivateMode.since(a)) : "Off");
        priv.setTint(a.getColor(p ? R.color.accent : R.color.text));
        reminder.setSubtitle(MoodReminder.enabled(a) ? "21:30" : "Off");
        if (!Places.enabled(a)) {
            places.setSubtitle("Off");
        } else if (!Places.hasForeground(a) || !Places.hasBackground(a)) {
            places.setSubtitle("Needs \u201cAllow all the time\u201d");
        } else {
            int n = Places.count(a);
            places.setSubtitle(n == 0 ? "On" : "On · " + n + (n == 1 ? " place" : " places"));
        }
    }
}
