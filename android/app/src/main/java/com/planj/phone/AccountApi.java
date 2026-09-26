package com.planj.phone;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** The relay's account endpoints. Every call blocks; run it off the main thread. */
final class AccountApi {
    /** A refusal from the relay, with its status so callers can branch (403 = unverified). */
    static final class Refused extends IOException {
        final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static final class Session {
        final String email, token;
        final boolean hasKeybox;

        Session(String email, String token, boolean hasKeybox) {
            this.email = email;
            this.token = token;
            this.hasKeybox = hasKeybox;
        }
    }

    private AccountApi() {}

    static void register(String email, String password) throws IOException {
        call("POST", "/v1/account/register", json("email", AccountCrypto.normaliseEmail(email),
                "auth", AccountCrypto.authValue(email, password)), null);
    }

    static void resend(String email) throws IOException {
        call("POST", "/v1/account/resend", json("email", AccountCrypto.normaliseEmail(email)), null);
    }

    static Session verify(String email, String code, String device) throws IOException {
        return session(email, call("POST", "/v1/account/verify", json("email", AccountCrypto.normaliseEmail(email),
                "code", code.trim(), "device", device), null));
    }

    static Session login(String email, String password, String device) throws IOException {
        return session(email, call("POST", "/v1/account/login", json("email", AccountCrypto.normaliseEmail(email),
                "auth", AccountCrypto.authValue(email, password), "device", device), null));
    }

    static void forgot(String email) throws IOException {
        call("POST", "/v1/account/forgot", json("email", AccountCrypto.normaliseEmail(email)), null);
    }

    static Session reset(String email, String code, String newPassword, String device) throws IOException {
        return session(email, call("POST", "/v1/account/reset", json("email", AccountCrypto.normaliseEmail(email),
                "code", code.trim(), "auth", AccountCrypto.authValue(email, newPassword), "device", device), null));
    }

    /** null when no device has stored a key yet. */
    static JSONObject keybox(String token) throws IOException {
        try {
            return call("GET", "/v1/account/keybox", null, token);
        } catch (Refused r) {
            if (r.status == 404) return null;
            throw r;
        }
    }

    static void putKeybox(String token, String pw, String rc) throws IOException {
        call("PUT", "/v1/account/keybox", json("pw", pw, "rc", rc == null ? "" : rc), token);
    }

    static JSONObject me(String token) throws IOException {
        return call("GET", "/v1/account/me", null, token);
    }

    static void logout(String token) throws IOException {
        call("POST", "/v1/account/logout", null, token);
    }

    static void revokeDevice(String token, int index) throws IOException {
        call("DELETE", "/v1/account/device/" + index, null, token);
    }

    static void deleteAccount(String token) throws IOException {
        call("DELETE", "/v1/account/me", null, token);
    }

    static void changePassword(String token, String email, String oldPassword, String newPassword, byte[] accountKey) throws IOException {
        call("POST", "/v1/account/password", json(
                "auth", AccountCrypto.authValue(email, oldPassword),
                "new_auth", AccountCrypto.authValue(email, newPassword),
                "pw", AccountCrypto.wrap(accountKey, AccountCrypto.wrapKey(email, newPassword), AccountCrypto.AAD_PW)), token);
    }

    // ---- plumbing ----------------------------------------------------------

    private static Session session(String email, JSONObject out) throws IOException {
        try {
            return new Session(AccountCrypto.normaliseEmail(email), out.getString("token"), out.optBoolean("has_keybox"));
        } catch (JSONException e) {
            throw new IOException("unexpected answer from the relay");
        }
    }

    private static String json(String... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o.toString();
    }

    private static JSONObject call(String method, String path, String body, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(RelaySync.RELAY_URL + path).toURL().openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("Content-Type", "application/json");
            if (token != null) conn.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(b.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(b);
                }
            }
            int status = conn.getResponseCode();
            if (status / 100 != 2) {
                String msg = "";
                try (InputStream err = conn.getErrorStream()) {
                    if (err != null) msg = new String(readAll(err), StandardCharsets.UTF_8).trim();
                }
                throw new Refused(status, msg.isEmpty() ? "the relay answered " + status : msg);
            }
            String text;
            try (InputStream in = conn.getInputStream()) {
                text = new String(readAll(in), StandardCharsets.UTF_8).trim();
            }
            if (text.isEmpty()) return new JSONObject();
            try {
                return new JSONObject(text);
            } catch (JSONException e) {
                throw new IOException("unexpected answer from the relay");
            }
        } catch (java.net.UnknownHostException | java.net.ConnectException | java.net.SocketTimeoutException e) {
            throw new IOException("No connection — check your internet and try again");
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
        return out.toByteArray();
    }
}
