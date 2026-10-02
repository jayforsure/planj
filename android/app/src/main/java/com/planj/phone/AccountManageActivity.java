package com.planj.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Account and devices: sync, your devices, password and recovery code, signing out. */
public class AccountManageActivity extends Activity {
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
        if (!AccountStore.signedIn(this)) {
            finish();
            return;
        }
        render();
    }

    private void render() {
        page.clear();
        page.icon(null, R.drawable.ic_account);
        page.title("Account and devices");
        page.blurb(AccountStore.email(this));

        long synced = RelaySync.lastSyncMs(this), confirmed = RelaySync.confirmedMs(this);
        String pc = RelaySync.lastError(this) != null ? "retrying" : confirmed > 0 ? "PC confirmed " + Fmt.clock(confirmed)
                : RelaySync.confirmationOverdue(this) ? "PC not answering" : "waiting for the PC";
        page.section("Sync");
        page.link(R.drawable.ic_sync, "Sync now", (synced == 0 ? "Not synced yet" : "Phone synced " + Fmt.clock(synced)) + " · " + pc, () -> {
            android.widget.Toast.makeText(this, "Syncing", android.widget.Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                SnapshotJob.collectAndSync(this);
                runOnUiThread(this::render);
            }).start();
        });
        page.link(R.drawable.ic_devices, "Devices", DeviceNames.phone(this) + " · " + DeviceNames.pc(this), () -> open("devices"));

        page.section("Security");
        page.link(R.drawable.ic_lock, "Change password", "Other devices are signed out", () -> open("password"));
        page.link(R.drawable.ic_key, "Recovery code", AccountStore.hasRecovery(this) ? "On file · tap for a new one" : "None yet · tap to create one",
                () -> open("newrecovery"));
        page.link(R.drawable.ic_trash, "Delete account", "Every device loses access", () -> open("delete")).setTint(getColor(R.color.bad));
        page.secondary("Sign out on this phone", () -> Sheet.confirm(this, R.drawable.ic_account, "Sign out on this phone?",
                "Recorded data stays here. Syncing stops until you sign in again.", "Sign out", true, () -> {
                    String token = AccountStore.token(this);
                    AccountStore.signOut(this);
                    new Thread(() -> { try { AccountApi.logout(token); } catch (Exception ignored) {} }).start();
                    finish();
                }));
    }

    private void open(String screen) {
        startActivity(new Intent(this, AccountActivity.class).putExtra(AccountActivity.EXTRA_SCREEN, screen));
    }
}
