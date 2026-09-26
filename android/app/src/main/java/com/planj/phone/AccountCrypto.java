package com.planj.phone;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Account crypto, v2. The relay authenticates you with {@code auth}; the data key only ever
 * leaves a device wrapped with {@code wrap} (from the password) or the recovery code.
 * Byte-identical with planj/account.py on the PC, which documents the scheme and holds the
 * shared vectors.
 */
final class AccountCrypto {
    private static final int ROUNDS = 200_000;
    private static final int CODE_CHARS = 20;
    private static final byte[] SALT = "planj-account-v2".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    static final String AAD_PW = "planj-keybox-pw";
    static final String AAD_RC = "planj-keybox-rc";

    private AccountCrypto() {}

    static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static byte[] master(String secret, String saltPrefix, String email) {
        String e = normaliseEmail(email);
        if (e.isEmpty() || secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("Email and password are both needed");
        }
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new PBEKeySpec(secret.toCharArray(),
                    (saltPrefix + e).getBytes(StandardCharsets.UTF_8), ROUNDS, 256)).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

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
            throw new IllegalStateException(e);
        }
    }

    /** What the relay checks at sign-in. Reveals nothing about {@link #wrapKey}. */
    static String authValue(String email, String password) {
        return RelayCrypto.hex(hkdf(master(password, "planj-account-v2|", email), "auth"));
    }

    static byte[] wrapKey(String email, String password) {
        return hkdf(master(password, "planj-account-v2|", email), "wrap");
    }

    static byte[] recoveryWrapKey(String email, String recoveryCode) {
        return hkdf(master(RelayCrypto.normalize(recoveryCode), "planj-recovery-v2|", email), "wrap");
    }

    static byte[] newAccountKey() {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return k;
    }

    static String newRecoveryCode() {
        byte[] r = new byte[13];
        RANDOM.nextBytes(r);
        return codeFromBits(r);
    }

    /** The pairing code the relay mailbox and data key derive from: the key's first 100 bits. */
    static String pairingCode(byte[] accountKey) {
        return codeFromBits(accountKey);
    }

    private static String codeFromBits(byte[] raw) {
        StringBuilder sb = new StringBuilder();
        int bitPos = 0;
        for (int i = 0; i < CODE_CHARS; i++) {
            int v = 0;
            for (int b = 0; b < 5; b++, bitPos++) {
                v = (v << 1) | ((raw[bitPos / 8] >> (7 - bitPos % 8)) & 1);
            }
            sb.append(RelayCrypto.ALPHABET.charAt(v));
            if (i % 5 == 4 && i < CODE_CHARS - 1) sb.append('-');
        }
        return sb.toString();
    }

    static String wrap(byte[] accountKey, byte[] key, String aad) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad.getBytes(StandardCharsets.US_ASCII));
            byte[] sealed = c.doFinal(accountKey);
            byte[] out = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @throws IllegalArgumentException when the key does not fit (wrong password or code). */
    static byte[] unwrap(String blob, byte[] key, String aad) {
        byte[] raw = Base64.decode(blob, Base64.DEFAULT);
        if (raw.length != 12 + 32 + 16) throw new IllegalArgumentException("The stored key is damaged");
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOf(raw, 12)));
            c.updateAAD(aad.getBytes(StandardCharsets.US_ASCII));
            return c.doFinal(raw, 12, raw.length - 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Wrong password or recovery code");
        }
    }
}
