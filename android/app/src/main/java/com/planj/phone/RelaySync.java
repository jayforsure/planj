package com.planj.phone;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
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

    static long lastSyncMs(Context ctx) {
        return prefs(ctx).getLong("last_sync_ms", 0);
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
            prefs(ctx).edit().putString("code", crypto.code).remove("last_error").apply();
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
