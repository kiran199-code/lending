package com.bank.lending.iam.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM with a random 96-bit nonce per value. Stored form: {@code v1:} + base64(nonce || ciphertext+tag).
 * The caller passes a context string (for example {@code mfa:42}) that is authenticated but not stored, so a
 * ciphertext copied from one row to another no longer decrypts.
 */
public final class SecretCrypto {
    private static final String PREFIX = "v1:";
    private static final int NONCE = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretCrypto(byte[] key32) {
        if (key32 == null || key32.length != 32) {
            throw new IllegalArgumentException("The encryption key must be exactly 32 bytes (256 bits)");
        }
        this.key = new SecretKeySpec(key32, "AES");
    }

    /** @param base64 the key as base64 (standard or URL-safe); blank means "not configured" and fails fast. */
    public static SecretCrypto fromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("IAM_ENCRYPTION_KEY is required (32 random bytes, base64). Generate one with: openssl rand -base64 32");
        }
        byte[] raw;
        try {
            String t = base64.trim();
            raw = t.contains("-") || t.contains("_") ? Base64.getUrlDecoder().decode(t) : Base64.getDecoder().decode(t);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("IAM_ENCRYPTION_KEY is not valid base64");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("IAM_ENCRYPTION_KEY must decode to exactly 32 bytes, but decodes to " + raw.length);
        }
        return new SecretCrypto(raw);
    }

    public String encrypt(String plain, String context) {
        try {
            byte[] nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(NONCE + ct.length).put(nonce).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    /** @throws IllegalStateException when the value was not produced with this key and this context */
    public String decrypt(String stored, String context) {
        if (stored == null || !stored.startsWith(PREFIX)) throw new IllegalStateException("not an encrypted value");
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, NONCE));
            c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(all, NONCE, all.length - NONCE), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("cannot decrypt: wrong key or context, or the value was altered");
        }
    }

    public boolean canDecrypt(String stored, String context) {
        try {
            decrypt(stored, context);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
