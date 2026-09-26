package com.planj.phone;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Sign-in without a server: email + password derive the pairing code on the device, so every
 * device with the same credentials shares one encrypted mailbox and nothing about the account
 * ever leaves the phone. Keep byte-identical with planj/account.py on the PC.
 */
final class AccountKey {
    private static final int ROUNDS = 200_000;
    private static final int CODE_CHARS = 20;

    private AccountKey() {}

    static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    static String pairingCode(String email, String password) {
        String e = normaliseEmail(email);
        if (e.isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Email and password are both needed");
        }
        byte[] salt = ("planj-account-v1|" + e).getBytes(StandardCharsets.UTF_8);
        byte[] master;
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            master = f.generateSecret(new PBEKeySpec(password.toCharArray(), salt, ROUNDS, 256)).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
        // The first 100 bits, five at a time, most significant first.
        StringBuilder sb = new StringBuilder();
        int bitPos = 0;
        for (int i = 0; i < CODE_CHARS; i++) {
            int v = 0;
            for (int b = 0; b < 5; b++, bitPos++) {
                v = (v << 1) | ((master[bitPos / 8] >> (7 - bitPos % 8)) & 1);
            }
            sb.append(RelayCrypto.ALPHABET.charAt(v));
            if (i % 5 == 4 && i < CODE_CHARS - 1) sb.append('-');
        }
        return sb.toString();
    }
}
