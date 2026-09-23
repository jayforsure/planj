package com.planj.phone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end encryption for relay sync. Must stay byte-compatible with the PC tracker
 * (tracker/relaysync.go), which documents the format and holds the shared test vector.
 * Plain Java only, so it can be checked against the Go side without an Android device.
 */
final class RelayCrypto {
    static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int CODE_LEN = 20;
    private static final byte[] SALT = "planj-relay-v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte BLOB_VERSION = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    final String code;
    final String mailbox; // this phone uploads here
    final String reply;   // the PC confirms receipt here
    private final byte[] key;

    private RelayCrypto(String code, String mailbox, String reply, byte[] key) {
        this.code = code;
        this.mailbox = mailbox;
        this.reply = reply;
        this.key = key;
    }

    /** Accepts any case, dashes or spaces, and the look-alikes I/L for 1 and O for 0. */
    static String normalize(String typed) {
        String c = typed.toUpperCase().replace("-", "").replace(" ", "")
                .replace('I', '1').replace('L', '1').replace('O', '0');
        if (c.length() != CODE_LEN) {
            throw new IllegalArgumentException("The code has " + CODE_LEN + " characters; you entered " + c.length());
        }
        for (int i = 0; i < c.length(); i++) {
            if (ALPHABET.indexOf(c.charAt(i)) < 0) {
                throw new IllegalArgumentException("\"" + c.charAt(i) + "\" is not used in pairing codes");
            }
        }
        return c;
    }

    static RelayCrypto derive(String typed) {
        String c = normalize(typed);
        byte[] ikm = c.getBytes(StandardCharsets.US_ASCII);
        String formatted = c.substring(0, 5) + "-" + c.substring(5, 10) + "-" + c.substring(10, 15) + "-" + c.substring(15);
        return new RelayCrypto(formatted, hex(hkdf(ikm, "mailbox")), hex(hkdf(ikm, "mailbox-reply")),
                hkdf(ikm, "aes-256-gcm"));
    }

    /** HKDF-SHA256 (RFC 5869) for a 32-byte output, which is a single expand block. */
    private static byte[] hkdf(byte[] ikm, String info) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SALT, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(info.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) 1);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e); // HmacSHA256 is always available
        }
    }

    byte[] seal(byte[] jsonl) throws IOException {
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(z)) {
            gz.write(jsonl);
        }
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(mailbox.getBytes(StandardCharsets.US_ASCII));
            byte[] sealed = cipher.doFinal(z.toByteArray());
            byte[] out = new byte[1 + nonce.length + sealed.length];
            out[0] = BLOB_VERSION;
            System.arraycopy(nonce, 0, out, 1, nonce.length);
            System.arraycopy(sealed, 0, out, 1 + nonce.length, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** Opens a blob sealed for this pairing, e.g. the PC's confirmation. */
    byte[] open(byte[] blob, String aadMailbox) throws IOException {
        if (blob.length < 1 + 12 + 16 || blob[0] != BLOB_VERSION) throw new IOException("not a planj blob");
        byte[] nonce = java.util.Arrays.copyOfRange(blob, 1, 13);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aadMailbox.getBytes(StandardCharsets.US_ASCII));
            byte[] gz = cipher.doFinal(blob, 13, blob.length - 13);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz))) {
                byte[] buf = new byte[8192];
                for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
            }
            return out.toByteArray();
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** For the cross-language check only: the derived key, hex-encoded. */
    String keyHexForTest() {
        return hex(key);
    }
}
