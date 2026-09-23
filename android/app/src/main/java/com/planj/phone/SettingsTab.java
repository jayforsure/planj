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
    private final Button grant, reminder, pair, export;

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
        pair = root.findViewById(R.id.btn_pair);
        pair.setOnClickListener(v -> askPairingCode());
        export = root.findViewById(R.id.btn_export);
        export.setOnClickListener(v -> a.startExport());
    }

    View view() {
        return root;
    }

    void refresh(boolean granted) {
        setDot(dotTracking, granted ? R.color.ok : R.color.warn);
        trackingDetail.setText(granted ? "On" : "Usage access is off");
        grant.setVisibility(granted ? View.GONE : View.VISIBLE);
        export.setEnabled(granted);

        String code = RelaySync.pairedCode(a);
        String error = RelaySync.lastError(a);
        long synced = RelaySync.lastSyncMs(a);
        long confirmed = RelaySync.confirmedMs(a);
        pair.setText(code == null ? "Pair with PC" : "Change PC pairing code");
        if (code == null) {
            setDot(dotSync, R.color.idle);
            syncDetail.setText("Not paired");
        } else if (error != null) {
            setDot(dotSync, R.color.warn);
            syncDetail.setText("Retrying · " + error);
        } else if (confirmed > 0) {
            setDot(dotSync, R.color.ok);
            syncDetail.setText("Confirmed " + Fmt.clock(confirmed));
        } else if (RelaySync.confirmationOverdue(a)) {
            setDot(dotSync, R.color.warn);
            syncDetail.setText("PC not answering — check code");
        } else {
            setDot(dotSync, R.color.idle);
            syncDetail.setText(synced == 0 ? "Waiting for first sync" : "Waiting for PC");
        }
        reminder.setVisibility(MoodReminder.enabled(a) ? View.GONE : View.VISIBLE);
    }

    private void setDot(View dot, int colorRes) {
        dot.getBackground().mutate().setTint(a.getColor(colorRes));
    }

    private void askPairingCode() {
        EditText input = new EditText(a);
        input.setHint("XXXXX-XXXXX-XXXXX-XXXXX");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int pad = Math.round(24 * a.getResources().getDisplayMetrics().density);
        LinearLayout wrap = new LinearLayout(a);
        wrap.setPadding(pad, pad / 3, pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Pair with PC")
                .setMessage("On your PC, open planj-tracker again to see its 20-character code, then type it here.")
                .setView(wrap)
                .setPositiveButton("Pair", (d, w) -> {
                    try {
                        RelaySync.pair(a, input.getText().toString());
                        a.toast("Paired — sending your history to the PC");
                        a.syncInBackground();
                    } catch (IllegalArgumentException e) {
                        a.toast(e.getMessage());
                    } catch (Exception e) {
                        a.toast("Could not pair: " + e.getMessage());
                    }
                    a.refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
