package com.planj.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The names of this account's devices, as the person set them ("Device 1" until renamed).
 * Kept on the phone so Today can title its sections at once and offline; refreshed from the
 * account whenever the app opens or a device is renamed.
 */
final class DeviceNames {
    private static final String PREFS = "planj_devices";

    private DeviceNames() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** What a device is called: its label, else "Device N" by its place in the account. */
    static String display(JSONObject d) {
        String label = d.optString("label").trim();
        return label.isEmpty() ? "Device " + (d.optInt("index") + 1) : label;
    }

    /** This phone's name; "Phone" before signing in. */
    static String phone(Context ctx) {
        return prefs(ctx).getString("this", "Phone");
    }

    /** The computer's name: the account's other device; "PC" when there is none yet. */
    static String pc(Context ctx) {
        return prefs(ctx).getString("other", "PC");
    }

    /** The computer reported in (live status or a quarter-hour check-in) at this time. */
    static void seenPc(Context ctx, long ms) {
        if (ms > prefs(ctx).getLong("pc_seen", 0)) prefs(ctx).edit().putLong("pc_seen", ms).apply();
    }

    /**
     * When another device was last active. The account only hears from the computer when it
     * signs in; its live status and check-ins go through the encrypted mailbox instead, so the
     * newest of all three is the truth. With more than one other device the mailbox cannot say
     * which one spoke, so only the account's own time is used.
     */
    static long lastActive(Context ctx, JSONObject d, int otherDevices) {
        long account = 0;
        try {
            account = java.time.OffsetDateTime.parse(d.optString("last_seen")).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // no time from the account
        }
        if (d.optBoolean("this") || otherDevices != 1) return account;
        return Math.max(account, Math.max(prefs(ctx).getLong("pc_seen", 0), RelaySync.confirmedMs(ctx)));
    }

    /** Stores the names from an account "me" answer. */
    static void save(Context ctx, JSONArray devices) {
        String mine = null, other = null;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.optJSONObject(i);
            if (d == null) continue;
            if (d.optBoolean("this")) mine = display(d);
            else if (other == null) other = display(d);
        }
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (mine != null) e.putString("this", mine);
        if (other != null) e.putString("other", other);
        else e.remove("other");
        e.apply();
    }

    /** Fetches the names again; call off the main thread. Quietly does nothing when offline. */
    static void refresh(Context ctx) {
        String token = AccountStore.token(ctx);
        if (token == null) return;
        try {
            save(ctx, AccountApi.me(token).getJSONArray("devices"));
        } catch (Exception e) {
            // keep the last names we had
        }
    }
}
