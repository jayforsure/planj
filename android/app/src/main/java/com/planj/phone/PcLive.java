package com.planj.phone;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What the PC is doing right now, read from the relay's live slot. The PC replaces it within a
 * second of any change and every 30 seconds otherwise, sealed with the account key like
 * everything else, so the relay only ever holds an unreadable blob.
 */
final class PcLive {
    /** Older than this, the PC has stopped reporting (asleep, off, or offline). */
    static final long STALE_MS = 90_000;

    final long atMs, sinceMs;
    final String name, cat;
    final boolean idle, locked;
    final List<PcDays.App> apps = new ArrayList<>();
    final int totalMinutes;

    private PcLive(JSONObject o) throws Exception {
        atMs = Instant.parse(o.getString("t")).toEpochMilli();
        JSONObject now = o.getJSONObject("now");
        name = now.optString("name");
        cat = now.optString("cat", "other");
        sinceMs = Instant.parse(now.getString("since")).toEpochMilli();
        idle = now.optBoolean("idle");
        locked = now.optBoolean("locked");
        int total = 0;
        JSONArray a = o.getJSONObject("day").optJSONArray("apps");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONArray x = a.getJSONArray(i);
                apps.add(new PcDays.App(x.getString(0), x.getInt(1), x.getString(2)));
                total += x.getInt(1);
            }
        }
        totalMinutes = total;
    }

    boolean fresh() {
        return System.currentTimeMillis() - atMs < STALE_MS;
    }

    /** In front and in use: not idle, not the lock screen, and still reporting. */
    boolean active() {
        return fresh() && !idle && !locked;
    }

    /**
     * The newest status, or null when not signed in, the PC has not written lately, or the
     * network is down. Today's totals from it are also kept, so the timeline and lists agree.
     */
    static PcLive fetch(Context ctx) {
        String code = RelaySync.pairedCode(ctx);
        if (code == null) return null;
        HttpURLConnection conn = null;
        try {
            RelayCrypto crypto = RelayCrypto.derive(code);
            conn = (HttpURLConnection) URI.create(RelaySync.RELAY_URL + "/v1/live/" + crypto.reply).toURL().openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);
            conn.setUseCaches(false);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            byte[] blob;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
                blob = out.toByteArray();
            }
            String text = new String(crypto.open(blob, crypto.mailbox), StandardCharsets.UTF_8).trim();
            JSONObject o = new JSONObject(text);
            PcDays.save(ctx, o.getJSONObject("day").toString());
            return new PcLive(o);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
