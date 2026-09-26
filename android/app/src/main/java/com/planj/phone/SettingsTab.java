package com.planj.phone;

import android.app.AlertDialog;
import android.content.Intent;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class SettingsTab {
    private final MainActivity a;
    private final View root;
    private final View dotTracking, dotSync;
    private final TextView trackingDetail, syncDetail;
    private final Button grant, reminder, export;
    private final TextView privateState;
    private final Button privateToggle;

    SettingsTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_settings, container, false);
        container.addView(root);
        dotTracking = root.findViewById(R.id.dot_tracking);
        dotSync = root.findViewById(R.id.dot_sync);
        trackingDetail = root.findViewById(R.id.tracking_detail);
        syncDetail = root.findViewById(R.id.sync_detail);
        grant = root.findViewById(R.id.btn_grant);
        grant.setOnClickListener(v -> a.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        reminder = root.findViewById(R.id.btn_reminder);
        reminder.setOnClickListener(v -> a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName())));
        export = root.findViewById(R.id.btn_export);
        export.setOnClickListener(v -> a.startExport());

        privateState = root.findViewById(R.id.private_state);
        privateToggle = root.findViewById(R.id.btn_private);
        privateToggle.setOnClickListener(v -> a.setPrivate(!PrivateMode.isOn(a)));
        root.findViewById(R.id.btn_private_apps).setOnClickListener(v ->
                a.startActivity(new Intent(a, AppPickerActivity.class)));

        Button tile = root.findViewById(R.id.btn_tile);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            tile.setOnClickListener(v -> a.getSystemService(android.app.StatusBarManager.class).requestAddTileService(
                    new android.content.ComponentName(a, PrivateTileService.class), "planj Private",
                    android.graphics.drawable.Icon.createWithResource(a, R.drawable.ic_private),
                    a.getMainExecutor(), result -> {}));
        } else {
            tile.setOnClickListener(v -> a.toast("Swipe down, tap the pencil, and drag “planj Private” into your tiles"));
        }
    }

    View view() {
        return root;
    }

    void refresh(boolean granted) {
        setDot(dotTracking, granted ? R.color.ok : R.color.warn);
        trackingDetail.setText(granted ? "On" : "Usage access is off");
        grant.setVisibility(granted ? View.GONE : View.VISIBLE);
        export.setEnabled(granted);

        String error = RelaySync.lastError(a);
        long confirmed = RelaySync.confirmedMs(a);
        if (RelaySync.pairedCode(a) == null) {
            setDot(dotSync, R.color.idle);
            syncDetail.setText("Not signed in");
        } else if (error != null) {
            setDot(dotSync, R.color.warn);
            syncDetail.setText("Retrying · " + error);
        } else if (confirmed > 0) {
            setDot(dotSync, R.color.ok);
            syncDetail.setText("PC confirmed " + Fmt.clock(confirmed));
        } else {
            setDot(dotSync, R.color.idle);
            syncDetail.setText("Waiting for PC");
        }
        reminder.setVisibility(MoodReminder.enabled(a) ? View.GONE : View.VISIBLE);

        boolean priv = PrivateMode.isOn(a);
        privateState.setText(priv ? "Private mode is on — since " + Fmt.clock(PrivateMode.since(a)) : "Recording normally");
        privateToggle.setText(priv ? "Resume recording" : "Turn on private mode");

    }

    private void setDot(View dot, int colorRes) {
        dot.getBackground().mutate().setTint(a.getColor(colorRes));
    }

}
