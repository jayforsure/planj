package com.planj.phone;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The phone's own "now" and today's app totals, put in the relay's live slot the same way the
 * PC does, sealed with the account key, so a computer can show what the phone is doing.
 */
final class PhoneLive {
    private PhoneLive() {}

    /**
     * Sends the phone's status. pkg is the app in front (null when the screen is off), since
     * when it has been there. Quietly does nothing when signed out or offline.
     */
    static void publish(Context ctx, String pkg, long sinceMs) {
        String code = RelaySync.pairedCode(ctx);
        if (code == null || PrivateMode.isOn(ctx)) return;
        HttpURLConnection conn = null;
        try {
            long now = System.currentTimeMillis();
            DayUsage u = DayUsage.load(ctx, LocalDate.now());
            JSONArray apps = new JSONArray();
            List<Map.Entry<String, Long>> ranked = u.ranked();
            for (int i = 0; i < Math.min(40, ranked.size()); i++) {
                Map.Entry<String, Long> e = ranked.get(i);
                if (e.getValue() < 60_000) break;
                apps.put(new JSONArray().put(AppPalette.label(ctx, e.getKey())).put(Math.round(e.getValue() / 60_000.0)));
            }
            JSONObject nowObj = new JSONObject()
                    .put("name", pkg == null ? "" : AppPalette.label(ctx, pkg))
                    .put("since", Instant.ofEpochMilli(sinceMs).toString())
                    .put("locked", pkg == null);
            JSONObject o = new JSONObject().put("event", "phone_live").put("t", Instant.ofEpochMilli(now).toString())
                    .put("now", nowObj)
                    .put("day", new JSONObject().put("day", LocalDate.now().toString()).put("apps", apps)
                            .put("screen_min", u.screenMs / 60_000).put("unlocks", u.unlocks));
            RelayCrypto crypto = RelayCrypto.derive(code);
            byte[] blob = crypto.seal((o + "\n").getBytes(StandardCharsets.UTF_8));
            conn = (HttpURLConnection) URI.create(RelaySync.RELAY_URL + "/v1/live/" + crypto.mailbox).toURL().openConnection();
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setFixedLengthStreamingMode(blob.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(blob);
            }
            conn.getResponseCode();
        } catch (Exception e) {
            // the next status replaces this one anyway
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** From the background: the app in front is the last one opened, if the screen is on. */
    static void publishFromEvents(Context ctx) {
        DayUsage u = DayUsage.load(ctx, LocalDate.now());
        long now = System.currentTimeMillis();
        boolean screenOn = !u.screenOn.isEmpty() && now - u.screenOn.get(u.screenOn.size() - 1)[1] < 5_000;
        if (screenOn && !u.sessions.isEmpty()) {
            DayUsage.Session s = u.sessions.get(u.sessions.size() - 1);
            publish(ctx, s.pkg, s.startMs);
        } else {
            long since = u.screenOn.isEmpty() ? now : u.screenOn.get(u.screenOn.size() - 1)[1];
            publish(ctx, null, since);
        }
    }
}
