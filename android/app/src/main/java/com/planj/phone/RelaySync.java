package com.planj.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Sends new usage events and moods to the PC through the relay, end-to-end encrypted.
 * Data waits in an outbox file and is only removed after the relay confirms receipt,
 * so nothing is lost while offline.
 */
final class RelaySync {
    // Where the relay is deployed; empty disables sync.
    static final String RELAY_URL = "https://planj-relay-production.up.railway.app";

    private static final String PREFS = "planj_relay";
    private static final int CHUNK_BYTES = 2 << 20; // stays well under the relay's 4 MB limit
    private static final Object LOCK = new Object();

    private RelaySync() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File outbox(Context ctx) {
        return new File(ctx.getFilesDir(), "outbox.jsonl");
    }

    static String pairedCode(Context ctx) {
        return prefs(ctx).getString("code", null);
    }

    static String signedInEmail(Context ctx) {
        return prefs(ctx).getString("email", null);
    }

    static void setSignedInEmail(Context ctx, String email) {
        prefs(ctx).edit().putString("email", email).apply();
    }

    /** Forgets the key and the queue; recorded data stays on the phone. */
    static void signOut(Context ctx) {
        synchronized (LOCK) {
            prefs(ctx).edit().clear().apply();
            outbox(ctx).delete();
        }
    }

    static long lastSyncMs(Context ctx) {
        return prefs(ctx).getLong("last_sync_ms", 0);
    }

    /** When the PC last confirmed receipt; 0 means it never has, which usually means a wrong code. */
    static long confirmedMs(Context ctx) {
        return prefs(ctx).getLong("confirmed_ms", 0);
    }

    /** The PC confirms every 15 minutes, so silence only means trouble after a grace period. */
    static boolean confirmationOverdue(Context ctx) {
        long paired = prefs(ctx).getLong("paired_ms", 0);
        return confirmedMs(ctx) == 0 && paired > 0 && System.currentTimeMillis() - paired > 20 * 60_000L;
    }

    static String lastError(Context ctx) {
        return prefs(ctx).getString("last_error", null);
    }

    /** Pairs with a PC and queues the full history so the PC starts complete. */
    static void pair(Context ctx, String typedCode) throws IOException {
        RelayCrypto crypto = RelayCrypto.derive(typedCode); // throws on a malformed code
        synchronized (LOCK) {
            File[] files = UsageCollector.eventsDir(ctx).listFiles((d, name) -> name.endsWith(".jsonl"));
            try (OutputStream out = new FileOutputStream(outbox(ctx), false)) {
                if (files != null) {
                    Arrays.sort(files);
                    for (File f : files) out.write(Files.readAllBytes(f.toPath()));
                }
            }
            prefs(ctx).edit().putString("code", crypto.code).putLong("paired_ms", System.currentTimeMillis())
                    .remove("confirmed_ms").remove("last_error").remove("email").apply();
        }
    }

    /** Queues freshly recorded lines for upload; a no-op until paired. */
    static void append(Context ctx, byte[] lines) throws IOException {
        if (lines.length == 0 || pairedCode(ctx) == null) return;
        synchronized (LOCK) {
            try (OutputStream out = new FileOutputStream(outbox(ctx), true)) {
                out.write(lines);
            }
        }
    }

    /** Uploads everything queued. Returns bytes sent. Failures are recorded for the UI and rethrown. */
    static long upload(Context ctx) throws IOException {
        String code = pairedCode(ctx);
        if (code == null || RELAY_URL.isEmpty()) return 0;
        RelayCrypto crypto = RelayCrypto.derive(code);
        long sent = 0;
        try {
            for (byte[] chunk; (chunk = nextChunk(ctx)).length > 0; ) {
                post(crypto, crypto.seal(chunk));
                synchronized (LOCK) {
                    dropPrefix(outbox(ctx), chunk.length);
                }
                sent += chunk.length;
            }
            prefs(ctx).edit().putLong("last_sync_ms", System.currentTimeMillis()).remove("last_error").apply();
            checkConfirmation(ctx, crypto);
            return sent;
        } catch (IOException e) {
            prefs(ctx).edit().putString("last_error", e.getMessage()).apply();
            throw e;
        }
    }

    /** Up to CHUNK_BYTES from the start of the outbox, ending on a line break. */
    private static byte[] nextChunk(Context ctx) throws IOException {
        synchronized (LOCK) {
            File f = outbox(ctx);
            if (!f.exists()) return new byte[0];
            byte[] buf = new byte[(int) Math.min(f.length(), CHUNK_BYTES)];
            try (InputStream in = new FileInputStream(f)) {
                int n = 0;
                for (int r; n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0; ) n += r;
                int end = n;
                while (end > 0 && buf[end - 1] != '\n') end--;
                return Arrays.copyOf(buf, end);
            }
        }
    }

    private static void dropPrefix(File f, long n) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (RandomAccessFile in = new RandomAccessFile(f, "r"); OutputStream out = new FileOutputStream(tmp)) {
            in.seek(n);
            byte[] buf = new byte[8192];
            for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
        }
        if (!tmp.renameTo(f)) throw new IOException("could not update outbox");
    }

    /** Reads the PC's confirmations, if any, and clears them from the relay. */
    private static void checkConfirmation(Context ctx, RelayCrypto crypto) throws IOException {
        String box = RELAY_URL + "/v1/mailbox/" + crypto.reply;
        HttpURLConnection conn = (HttpURLConnection) URI.create(box).toURL().openConnection();
        String body;
        try {
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return;
            try (InputStream in = conn.getInputStream()) {
                body = new String(readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }

        String lastId = null;
        try {
            JSONArray items = new JSONObject(body).getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                byte[] blob = Base64.decode(item.getString("data"), Base64.DEFAULT);
                String line = new String(crypto.open(blob, crypto.mailbox), StandardCharsets.UTF_8);
                if (line.contains("\"pc_ack\"")) {
                    prefs(ctx).edit().putLong("confirmed_ms", System.currentTimeMillis()).apply();
                }
                lastId = item.getString("id");
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        if (lastId != null) delete(box + "?upto=" + lastId);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
        return out.toByteArray();
    }

    private static void delete(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(15_000);
            conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    private static void post(RelayCrypto crypto, byte[] blob) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(RELAY_URL + "/v1/mailbox/" + crypto.mailbox).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setFixedLengthStreamingMode(blob.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(blob);
            }
            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_CREATED) throw new IOException("relay answered " + status);
        } finally {
            conn.disconnect();
        }
    }
}
