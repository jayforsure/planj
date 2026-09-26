package com.planj.phone;

import android.app.AlertDialog;
import android.content.Intent;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

/** The Account tab: a welcome hero when signed out, a Wise-style profile when signed in. */
final class AccountTab {
    static final int REQ_AVATAR = 7;

    private final MainActivity a;
    private final View root;
    private final View signedOut, profile;
    private final ListRow rowSync, rowDevices, rowRecovery, rowLegacy;
    private final TextView displayName, email, avatarInitial;
    private final ImageView avatarPhoto;
    private String cachedInfoFor;

    AccountTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_account, container, false);
        container.addView(root);
        signedOut = root.findViewById(R.id.card_signedout);
        profile = root.findViewById(R.id.card_profile);
        rowSync = root.findViewById(R.id.row_sync);
        rowDevices = root.findViewById(R.id.row_devices);
        rowRecovery = root.findViewById(R.id.row_recovery);
        rowLegacy = root.findViewById(R.id.row_legacy);
        displayName = root.findViewById(R.id.display_name);
        email = root.findViewById(R.id.email);
        avatarInitial = root.findViewById(R.id.avatar_initial);
        avatarPhoto = root.findViewById(R.id.avatar_photo);

        root.findViewById(R.id.hero_welcome).setOnClickListener(v -> open("create"));
        root.findViewById(R.id.row_signin).setOnClickListener(v -> open("signin"));
        root.findViewById(R.id.row_how).setOnClickListener(v -> new AlertDialog.Builder(a)
                .setTitle("How privacy works")
                .setMessage("Saved on this phone: which app is open, screen on/off, unlocks and your journal. Never notifications, messages, websites or what you type.\n\n"
                        + "Syncing sends that record to your PC through a relay, encrypted with a key derived on your devices. The relay stores only ciphertext and a hash used to sign you in. Nobody at planj can read your data — which is also why your password cannot be reset without your recovery code.")
                .setPositiveButton("Got it", null).show());
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
        displayName.setOnClickListener(v -> askName());

        rowSync.setOnClickListener(v -> a.syncInBackground());
        rowDevices.setOnClickListener(v -> open("devices"));
        root.findViewById(R.id.row_export).setOnClickListener(v -> a.startExport());
        root.findViewById(R.id.row_password).setOnClickListener(v -> open("password"));
        rowRecovery.setOnClickListener(v -> open("newrecovery"));
        root.findViewById(R.id.row_delete).setOnClickListener(v -> open("delete"));
        root.findViewById(R.id.hero_profile).setOnClickListener(v -> a.showOdds());
        root.findViewById(R.id.row_signout).setOnClickListener(v -> new AlertDialog.Builder(a)
                .setTitle("Sign out on this phone?")
                .setMessage("Recorded data stays here. Syncing stops until you sign in again.")
                .setPositiveButton("Sign out", (d, w) -> {
                    String token = AccountStore.token(a);
                    AccountStore.signOut(a);
                    new Thread(() -> { try { AccountApi.logout(token); } catch (Exception ignored) {} }).start();
                    a.refresh();
                })
                .setNegativeButton("Cancel", null).show());
    }

    private void askName() {
        EditText in = new EditText(a);
        in.setText(Avatar.name(a));
        in.setSelectAllOnFocus(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        in.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(a).setTitle("Your name").setView(in)
                .setPositiveButton("Save", (d, w) -> { Avatar.setName(a, in.getText().toString()); refresh(); })
                .setNegativeButton("Cancel", null).show();
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
            rowLegacy.setVisibility(RelaySync.pairedCode(a) != null ? View.VISIBLE : View.GONE);
            cachedInfoFor = null;
            return;
        }
        String e = AccountStore.email(a);
        email.setText(e);
        displayName.setText(Avatar.name(a));
        Avatar.show(a, avatarPhoto, avatarInitial);
        rowRecovery.setSubtitle(AccountStore.hasRecovery(a) ? "On file · tap for a new one" : "None yet · tap to create one");

        long synced = RelaySync.lastSyncMs(a);
        long confirmed = RelaySync.confirmedMs(a);
        String error = RelaySync.lastError(a);
        String pc = error != null ? "PC retrying" : confirmed > 0 ? "PC confirmed " + Fmt.clock(confirmed)
                : RelaySync.confirmationOverdue(a) ? "PC not answering — signed in there?" : "waiting for the PC";
        rowSync.setSubtitle((synced == 0 ? "Not synced yet" : "Phone synced " + Fmt.clock(synced)) + " · " + pc);

        if (!e.equals(cachedInfoFor)) {
            String token = AccountStore.token(a);
            new Thread(() -> {
                try {
                    JSONObject me = AccountApi.me(token);
                    int n = me.getJSONArray("devices").length();
                    a.runOnUiThread(() -> {
                        cachedInfoFor = e;
                        rowDevices.setSubtitle(n == 1 ? "Just this phone" : n + " signed in");
                        AccountStore.setHasRecovery(a, me.optBoolean("has_recovery"));
                        rowRecovery.setSubtitle(me.optBoolean("has_recovery") ? "On file · tap for a new one" : "None yet · tap to create one");
                    });
                } catch (AccountApi.Refused r) {
                    if (r.status == 401) a.runOnUiThread(() -> rowDevices.setSubtitle("Session ended — sign in again"));
                } catch (Exception ignored) {
                    // offline: the cached state stays
                }
            }).start();
        }
    }
}
