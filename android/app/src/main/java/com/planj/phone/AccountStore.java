package com.planj.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import java.io.IOException;

/** The signed-in session on this phone: email, relay token and the data key. */
final class AccountStore {
    private static final String PREFS = "planj_account";

    private AccountStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean signedIn(Context ctx) {
        return token(ctx) != null && accountKey(ctx) != null;
    }

    static String email(Context ctx) {
        return prefs(ctx).getString("email", null);
    }

    static String token(Context ctx) {
        return prefs(ctx).getString("token", null);
    }

    static byte[] accountKey(Context ctx) {
        String b = prefs(ctx).getString("account_key", null);
        return b == null ? null : Base64.decode(b, Base64.DEFAULT);
    }

    static boolean hasRecovery(Context ctx) {
        return prefs(ctx).getBoolean("has_recovery", false);
    }

    static String deviceName() {
        String name = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        return name.isEmpty() ? "Phone" : name.substring(0, Math.min(40, name.length()));
    }

    /** Stores the session and joins this phone to the account's shared record. */
    static void signIn(Context ctx, AccountApi.Session s, byte[] accountKey, boolean hasRecovery) throws IOException {
        RelaySync.pair(ctx, AccountCrypto.pairingCode(accountKey));
        RelaySync.setSignedInEmail(ctx, s.email);
        prefs(ctx).edit().putString("email", s.email).putString("token", s.token)
                .putString("account_key", Base64.encodeToString(accountKey, Base64.NO_WRAP))
                .putBoolean("has_recovery", hasRecovery).apply();
    }

    static void setHasRecovery(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean("has_recovery", v).apply();
    }

    static void setToken(Context ctx, String token) {
        prefs(ctx).edit().putString("token", token).apply();
    }

    /** Forgets everything about the account here; recorded data stays on the phone. */
    static void signOut(Context ctx) {
        prefs(ctx).edit().clear().apply();
        RelaySync.signOut(ctx);
    }
}
