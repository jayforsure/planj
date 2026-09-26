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
    private final ListRow tracking, priv, reminder;

    SettingsTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_settings, container, false);
        container.addView(root);
        tracking = root.findViewById(R.id.row_tracking);
        priv = root.findViewById(R.id.row_private);
        reminder = root.findViewById(R.id.row_reminder);

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
        tracking.setSubtitle(granted ? "On · which app is open, and for how long" : "Off · tap to allow usage access");
        tracking.setTint(a.getColor(granted ? R.color.text : R.color.warn));
        boolean p = PrivateMode.isOn(a);
        priv.setSubtitle(p ? "On since " + Fmt.clock(PrivateMode.since(a)) + " · tap to resume recording" : "Off · tap to pause all recording");
        priv.setTint(a.getColor(p ? R.color.accent : R.color.text));
        reminder.setSubtitle(MoodReminder.enabled(a) ? "On · a one-tap check-in around 21:30" : "Off · tap to allow notifications");
    }
}
