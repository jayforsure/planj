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
    private Button signin;
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
        pair = root.findViewById(R.id.btn_pair);
        pair.setOnClickListener(v -> askPairingCode());
        signin = root.findViewById(R.id.btn_signin);
        signin.setOnClickListener(v -> askSignIn());
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

        String code = RelaySync.pairedCode(a);
        String error = RelaySync.lastError(a);
        long synced = RelaySync.lastSyncMs(a);
        long confirmed = RelaySync.confirmedMs(a);
        String email = RelaySync.signedInEmail(a);
        signin.setText(email != null ? "Signed in as " + email + " · change" : code != null ? "Sign in (currently paired by code)" : "Sign in");
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

        boolean priv = PrivateMode.isOn(a);
        privateState.setText(priv ? "Private mode is on — since " + Fmt.clock(PrivateMode.since(a)) : "Recording normally");
        privateToggle.setText(priv ? "Resume recording" : "Turn on private mode");

    }

    private void setDot(View dot, int colorRes) {
        dot.getBackground().mutate().setTint(a.getColor(colorRes));
    }

    private void askSignIn() {
        int pad = Math.round(24 * a.getResources().getDisplayMetrics().density);
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, pad / 3, pad, 0);
        EditText email = new EditText(a);
        email.setHint("Email");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        String current = RelaySync.signedInEmail(a);
        if (current != null) email.setText(current);
        EditText password = new EditText(a);
        password.setHint("Password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        wrap.addView(email, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        wrap.addView(password, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Sign in")
                .setMessage("Use the same email and password on every device you want joined. The password never leaves this phone — it only derives your key here, so it cannot be reset. Choose one you will keep.")
                .setView(wrap)
                .setPositiveButton("Sign in", (d, w) -> {
                    String e = email.getText().toString(), p = password.getText().toString();
                    a.toast("Deriving your key…");
                    new Thread(() -> {
                        try {
                            String code = AccountKey.pairingCode(e, p);
                            RelaySync.pair(a, code);
                            RelaySync.setSignedInEmail(a, AccountKey.normaliseEmail(e));
                            a.toast("Signed in — sending your history to the other devices");
                            a.syncInBackground();
                        } catch (IllegalArgumentException ex) {
                            a.toast(ex.getMessage());
                        } catch (Exception ex) {
                            a.toast("Could not sign in: " + ex.getMessage());
                        }
                        a.runOnUiThread(a::refresh);
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
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
