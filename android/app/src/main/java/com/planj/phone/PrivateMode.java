package com.planj.phone;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/**
 * Private mode records nothing app-level until turned off. The start and end are written
 * to the event log so history shows "private" rather than a suspicious blank.
 */
final class PrivateMode {
    private static final String PREFS = "planj_private";
    // Apps that are private by default: money, passwords, health and dating. Users can change it.
    static final String[] DEFAULT_PRIVATE = {
            "bank", "maybank", "cimb", "publicbank", "rhb", "hongleong", "ambank", "ocbc", "uob", "hsbc",
            "wallet", "touchngo", "tng", "boost", "grabpay", "paypal", "wise", "revolut",
            "1password", "bitwarden", "lastpass", "keepass", "authenticator",
            "health", "medical", "clinic",
            "tinder", "bumble", "hinge", "grindr", "okcupid",
    };

    private PrivateMode() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isOn(Context ctx) {
        return prefs(ctx).getLong("since", 0) > 0;
    }

    static long since(Context ctx) {
        return prefs(ctx).getLong("since", 0);
    }

    static synchronized void set(Context ctx, boolean on) {
        if (on == isOn(ctx)) return;
        long now = System.currentTimeMillis();
        try {
            append(ctx, now, on ? "private_on" : "private_off");
        } catch (IOException e) {
            // the toggle still applies; the marker is best-effort
        }
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (on) {
            e.putLong("since", now);
        } else {
            e.remove("since").putLong("last_end", now).putLong("last_start", since(ctx));
        }
        e.apply();
    }

    /** Whether an event at {@code t} falls inside a private window and must not be recorded. */
    static boolean covers(Context ctx, long t) {
        SharedPreferences p = prefs(ctx);
        long since = p.getLong("since", 0);
        if (since > 0 && t >= since) return true;
        long lastStart = p.getLong("last_start", 0), lastEnd = p.getLong("last_end", 0);
        return lastStart > 0 && t >= lastStart && t < lastEnd;
    }

    /** Package substrings the user has marked private; falls back to the defaults. */
    static Set<String> privateApps(Context ctx) {
        Set<String> saved = prefs(ctx).getStringSet("apps", null);
        return saved != null ? new HashSet<>(saved) : new HashSet<>(java.util.Arrays.asList(DEFAULT_PRIVATE));
    }

    static void setPrivateApps(Context ctx, Set<String> packages) {
        prefs(ctx).edit().putStringSet("apps", new HashSet<>(packages)).apply();
    }

    static boolean isPrivateApp(Context ctx, String pkg) {
        String low = pkg.toLowerCase();
        for (String needle : privateApps(ctx)) {
            if (low.contains(needle.toLowerCase())) return true;
        }
        return false;
    }

    private static void append(Context ctx, long t, String event) throws IOException {
        JSONObject o;
        try {
            o = new JSONObject().put("t", Instant.ofEpochMilli(t).toString()).put("event", event);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        File dir = UsageCollector.eventsDir(ctx);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        String day = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate().toString();
        byte[] bytes = (o + "\n").getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = new FileOutputStream(new File(dir, day + ".jsonl"), true)) {
            out.write(bytes);
        }
        RelaySync.append(ctx, bytes);
    }
}
