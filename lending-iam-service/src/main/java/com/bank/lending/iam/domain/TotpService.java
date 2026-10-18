package com.bank.lending.iam.domain;

import com.bank.lending.iam.common.Hashing;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Time-based one-time passwords, RFC 6238 (HMAC-SHA1, 6 digits, 30-second step) with one step of drift either side. */
public final class TotpService {
    public static final int DIGITS = 6;
    public static final int STEP_SECONDS = 30;
    public static final int DRIFT_STEPS = 1;
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public enum Status { OK, INVALID, REPLAY }

    /** @param step the time step the code matched (meaningful when status is OK or REPLAY) */
    public record Result(Status status, long step) {}

    public String newSecret() {
        return base32Encode(Hashing.randomBytes(20));
    }

    public long stepAt(Instant t) {
        return Math.floorDiv(t.getEpochSecond(), STEP_SECONDS);
    }

    public String code(String base32Secret, long step) {
        try {
            byte[] key = base32Decode(base32Secret);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16) | ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            int mod = (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", bin % mod);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Checks a submitted code against the steps now-1, now and now+1.
     * A step that is not later than {@code lastUsedStep} is a replay and is refused.
     */
    public Result verify(String base32Secret, String submitted, Instant now, Long lastUsedStep) {
        if (submitted == null) return new Result(Status.INVALID, -1);
        String s = submitted.trim().replace(" ", "");
        if (s.length() != DIGITS || !s.chars().allMatch(Character::isDigit)) return new Result(Status.INVALID, -1);
        long current = stepAt(now);
        long matched = Long.MIN_VALUE;
        for (long step = current - DRIFT_STEPS; step <= current + DRIFT_STEPS; step++) {
            // evaluate all steps so the time taken does not show which one matched
            if (Hashing.constantTimeEquals(code(base32Secret, step), s)) matched = step;
        }
        if (matched == Long.MIN_VALUE) return new Result(Status.INVALID, -1);
        if (lastUsedStep != null && matched <= lastUsedStep) return new Result(Status.REPLAY, matched);
        return new Result(Status.OK, matched);
    }

    public String otpauthUri(String issuer, String account, String secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(B32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(B32.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    public static byte[] base32Decode(String s) {
        String t = s.trim().replace("=", "").replace(" ", "").toUpperCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (int i = 0; i < t.length(); i++) {
            int v = B32.indexOf(t.charAt(i));
            if (v < 0) throw new IllegalArgumentException("not base32");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
