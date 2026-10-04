package com.planj.phone;

import android.content.Intent;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * You: your profile (or a welcome when signed out), then four rows, each its own page:
 * connectors, privacy, account and devices, reminders.
 */
final class AccountTab {
    static final int REQ_AVATAR = 7;

    private final MainActivity a;
    private final View root;
    private final View signedOut, profile;
    private final ListRow rowLegacy, rowGoals, rowConnectors, rowPrivacy, rowManage, rowReminders;
    private final TextView displayName, email, avatarInitial, version;
    private final ImageView avatarPhoto;
    private String cachedInfoFor;
    private int devices;

    AccountTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_account, container, false);
        container.addView(root);
        signedOut = root.findViewById(R.id.card_signedout);
        profile = root.findViewById(R.id.card_profile);
        rowLegacy = root.findViewById(R.id.row_legacy);
        rowGoals = root.findViewById(R.id.row_goals);
        rowConnectors = root.findViewById(R.id.row_connectors);
        rowPrivacy = root.findViewById(R.id.row_privacy);
        rowManage = root.findViewById(R.id.row_manage);
        rowReminders = root.findViewById(R.id.row_reminders);
        displayName = root.findViewById(R.id.display_name);
        email = root.findViewById(R.id.email);
        avatarInitial = root.findViewById(R.id.avatar_initial);
        avatarPhoto = root.findViewById(R.id.avatar_photo);
        version = root.findViewById(R.id.version);

        root.findViewById(R.id.hero_welcome).setOnClickListener(v -> open("create"));
        root.findViewById(R.id.row_signin).setOnClickListener(v -> open("signin"));
        rowLegacy.setOnClickListener(v -> {
            RelaySync.signOut(a);
            a.toast("The older key is gone — create an account to sync again");
            a.refresh();
        });

        View.OnClickListener pick = v -> a.startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE), REQ_AVATAR);
        root.findViewById(R.id.avatar_camera).setOnClickListener(pick);
        avatarPhoto.setOnClickListener(pick);
        avatarInitial.setOnClickListener(pick);
        displayName.setOnClickListener(v -> open("name"));

        rowGoals.setOnClickListener(v -> a.startActivity(new Intent(a, GoalsActivity.class)));
        rowConnectors.setOnClickListener(v -> a.startActivity(new Intent(a, ConnectorsActivity.class)));
        rowPrivacy.setOnClickListener(v -> a.startActivity(new Intent(a, PrivacyActivity.class)));
        rowManage.setOnClickListener(v -> a.startActivity(new Intent(a, AccountManageActivity.class)));
        rowReminders.setOnClickListener(v -> a.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName())));
        version.setOnClickListener(v -> a.startActivity(new Intent(a, IntroActivity.class)));
        try {
            version.setText("planj " + a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName + " · how planj works");
        } catch (Exception e) {
            version.setText("How planj works");
        }
    }

    private void open(String screen) {
        a.startActivity(new Intent(a, AccountActivity.class).putExtra(AccountActivity.EXTRA_SCREEN, screen));
    }

    View view() {
        return root;
    }

    void refresh() {
        java.util.List<String> goals = new java.util.ArrayList<>();
        for (Goals.Goal g : Goals.chosen(a)) goals.add(g.name);
        rowGoals.setSubtitle(goals.isEmpty() ? "None yet" : String.join(", ", goals));
        int on = Connectors.connected(a).size();
        rowConnectors.setSubtitle(on == 0 ? "Nothing connected yet" : on + " connected");
        rowPrivacy.setSubtitle(PrivateMode.isOn(a) ? "Private mode on since " + Fmt.clock(PrivateMode.since(a)) : "Private mode off");
        rowReminders.setSubtitle(MoodReminder.enabled(a) ? "Evening check-in at 21:30" : "Off");

        boolean in = AccountStore.signedIn(a);
        signedOut.setVisibility(in ? View.GONE : View.VISIBLE);
        profile.setVisibility(in ? View.VISIBLE : View.GONE);
        rowManage.setVisibility(in ? View.VISIBLE : View.GONE);
        if (!in) {
            rowLegacy.setVisibility(RelaySync.pairedCode(a) != null ? View.VISIBLE : View.GONE);
            cachedInfoFor = null;
            return;
        }
        String e = AccountStore.email(a);
        email.setText(e);
        displayName.setText(Avatar.name(a));
        Avatar.show(a, avatarPhoto, avatarInitial);
        manageSubtitle();
        if (!e.equals(cachedInfoFor)) {
            String token = AccountStore.token(a);
            new Thread(() -> {
                try {
                    JSONObject me = AccountApi.me(token);
                    int n = me.getJSONArray("devices").length();
                    DeviceNames.save(a, me.getJSONArray("devices"));
                    a.runOnUiThread(() -> {
                        cachedInfoFor = e;
                        devices = n;
                        AccountStore.setHasRecovery(a, me.optBoolean("has_recovery"));
                        manageSubtitle();
                    });
                } catch (Exception ignored) {
                    // offline: the cached state stays
                }
            }).start();
        }
    }

    private void manageSubtitle() {
        long synced = RelaySync.lastSyncMs(a);
        String sync = RelaySync.lastError(a) != null ? "Sync retrying" : synced == 0 ? "Not synced yet" : "Synced " + Fmt.clock(synced);
        rowManage.setSubtitle(sync + (devices > 0 ? " · " + devices + (devices == 1 ? " device" : " devices") : ""));
    }
}
