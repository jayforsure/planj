package com.planj.phone;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONObject;

/** The Account tab: a welcome when signed out, a profile with sync, devices and security when in. */
final class AccountTab {
    private final MainActivity a;
    private final View root;
    private final View signedOut, profile, legacy, dotPc;
    private final TextView avatar, email, since, phoneDetail, pcDetail, devicesDetail, recoveryDetail;
    private String cachedInfoFor;

    AccountTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_account, container, false);
        container.addView(root);
        signedOut = root.findViewById(R.id.card_signedout);
        profile = root.findViewById(R.id.card_profile);
        legacy = root.findViewById(R.id.legacy);
        dotPc = root.findViewById(R.id.dot_pc);
        avatar = root.findViewById(R.id.avatar);
        email = root.findViewById(R.id.email);
        since = root.findViewById(R.id.since);
        phoneDetail = root.findViewById(R.id.phone_detail);
        pcDetail = root.findViewById(R.id.pc_detail);
        devicesDetail = root.findViewById(R.id.devices_detail);
        recoveryDetail = root.findViewById(R.id.recovery_detail);

        root.findViewById(R.id.btn_create).setOnClickListener(v -> open("create"));
        root.findViewById(R.id.btn_signin).setOnClickListener(v -> open("signin"));
        root.findViewById(R.id.btn_legacy_signout).setOnClickListener(v -> {
            RelaySync.signOut(a);
            a.toast("The older key is gone — create an account to sync again");
            a.refresh();
        });
        root.findViewById(R.id.row_devices).setOnClickListener(v -> open("devices"));
        root.findViewById(R.id.row_password).setOnClickListener(v -> open("password"));
        root.findViewById(R.id.row_recovery).setOnClickListener(v -> open("newrecovery"));
        root.findViewById(R.id.row_delete).setOnClickListener(v -> open("delete"));
        root.findViewById(R.id.row_signout).setOnClickListener(v -> {
            String token = AccountStore.token(a);
            AccountStore.signOut(a);
            new Thread(() -> { try { AccountApi.logout(token); } catch (Exception ignored) {} }).start();
            a.toast("Signed out — this phone keeps its data, and stops syncing");
            a.refresh();
        });
    }

    private void open(String screen) {
        a.startActivity(new Intent(a, AccountActivity.class).putExtra(AccountActivity.EXTRA_SCREEN, screen));
    }

    View view() {
        return root;
    }

    void refresh() {
        boolean in = AccountStore.signedIn(a);
        signedOut.setVisibility(in ? View.GONE : View.VISIBLE);
        profile.setVisibility(in ? View.VISIBLE : View.GONE);
        if (!in) {
            legacy.setVisibility(RelaySync.pairedCode(a) != null ? View.VISIBLE : View.GONE);
            cachedInfoFor = null;
            return;
        }
        String e = AccountStore.email(a);
        email.setText(e);
        avatar.setText(e.isEmpty() ? "?" : e.substring(0, 1).toUpperCase());
        recoveryDetail.setText(AccountStore.hasRecovery(a) ? "On file · tap to generate a new one" : "None yet · tap to create one");

        long synced = RelaySync.lastSyncMs(a);
        phoneDetail.setText(synced == 0 ? "not synced yet" : "synced " + Fmt.clock(synced));
        long confirmed = RelaySync.confirmedMs(a);
        String error = RelaySync.lastError(a);
        if (error != null) {
            dot(R.color.warn); pcDetail.setText("retrying");
        } else if (confirmed > 0) {
            dot(R.color.ok); pcDetail.setText("confirmed " + Fmt.clock(confirmed));
        } else if (RelaySync.confirmationOverdue(a)) {
            dot(R.color.warn); pcDetail.setText("not answering — signed in there?");
        } else {
            dot(R.color.idle); pcDetail.setText("waiting");
        }

        if (!e.equals(cachedInfoFor)) {
            String token = AccountStore.token(a);
            new Thread(() -> {
                try {
                    JSONObject me = AccountApi.me(token);
                    int n = me.getJSONArray("devices").length();
                    String created = me.optString("created");
                    a.runOnUiThread(() -> {
                        cachedInfoFor = e;
                        since.setText(created.length() >= 10 ? "Member since " + created.substring(0, 10) : "");
                        devicesDetail.setText(n == 1 ? "Just this phone" : n + " signed in");
                        AccountStore.setHasRecovery(a, me.optBoolean("has_recovery"));
                        recoveryDetail.setText(me.optBoolean("has_recovery") ? "On file · tap to generate a new one" : "None yet · tap to create one");
                    });
                } catch (AccountApi.Refused r) {
                    if (r.status == 401) a.runOnUiThread(() -> since.setText("Session ended — sign in again"));
                } catch (Exception ignored) {
                    // offline: the cached state stays
                }
            }).start();
        }
    }

    private void dot(int color) {
        dotPc.getBackground().mutate().setTint(a.getColor(color));
    }
}
