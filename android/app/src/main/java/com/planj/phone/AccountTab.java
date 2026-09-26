package com.planj.phone;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

final class AccountTab {
    private final MainActivity a;
    private final View root;
    private final TextView state, phoneDetail, pcDetail;
    private final View cardSignin, cardDevices, dotPc;
    private final EditText email, password;

    AccountTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_account, container, false);
        container.addView(root);
        state = root.findViewById(R.id.account_state);
        cardSignin = root.findViewById(R.id.card_signin);
        cardDevices = root.findViewById(R.id.card_devices);
        phoneDetail = root.findViewById(R.id.phone_detail);
        pcDetail = root.findViewById(R.id.pc_detail);
        dotPc = root.findViewById(R.id.dot_pc);
        email = root.findViewById(R.id.email);
        password = root.findViewById(R.id.password);
        root.findViewById(R.id.btn_signin).setOnClickListener(v -> signIn());
        root.findViewById(R.id.btn_signout).setOnClickListener(v -> {
            RelaySync.signOut(a);
            a.toast("Signed out — this phone keeps its data, and stops syncing");
            a.refresh();
        });
    }

    View view() {
        return root;
    }

    private void signIn() {
        String e = email.getText().toString(), p = password.getText().toString();
        a.toast("Deriving your key…");
        new Thread(() -> {
            try {
                String code = AccountKey.pairingCode(e, p);
                RelaySync.pair(a, code);
                RelaySync.setSignedInEmail(a, AccountKey.normaliseEmail(e));
                a.toast("Signed in");
                a.syncInBackground();
            } catch (IllegalArgumentException ex) {
                a.toast(ex.getMessage());
            } catch (Exception ex) {
                a.toast("Could not sign in: " + ex.getMessage());
            }
            a.runOnUiThread(() -> {
                password.setText("");
                a.refresh();
            });
        }).start();
    }

    void refresh() {
        String signedIn = RelaySync.signedInEmail(a);
        boolean in = signedIn != null || RelaySync.pairedCode(a) != null;
        cardSignin.setVisibility(in ? View.GONE : View.VISIBLE);
        cardDevices.setVisibility(in ? View.VISIBLE : View.GONE);
        if (!in) {
            state.setText("Not signed in — this phone records for itself only.");
            return;
        }
        state.setText(signedIn != null ? signedIn : "Joined with an older key — sign out, then sign in with your account on both devices.");
        long synced = RelaySync.lastSyncMs(a);
        phoneDetail.setText(synced == 0 ? "not synced yet" : "synced " + Fmt.clock(synced));
        long confirmed = RelaySync.confirmedMs(a);
        String error = RelaySync.lastError(a);
        if (error != null) {
            dotPc.getBackground().mutate().setTint(a.getColor(R.color.warn));
            pcDetail.setText("retrying");
        } else if (confirmed > 0) {
            dotPc.getBackground().mutate().setTint(a.getColor(R.color.ok));
            pcDetail.setText("confirmed " + Fmt.clock(confirmed));
        } else if (RelaySync.confirmationOverdue(a)) {
            dotPc.getBackground().mutate().setTint(a.getColor(R.color.warn));
            pcDetail.setText("not answering — signed in there?");
        } else {
            dotPc.getBackground().mutate().setTint(a.getColor(R.color.idle));
            pcDetail.setText("waiting");
        }
    }
}
