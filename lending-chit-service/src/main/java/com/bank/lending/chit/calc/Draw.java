package com.bank.lending.chit.calc;

import com.bank.lending.chit.common.ChitException;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A draw that anyone can replay. The result depends only on the seed and the list of candidate ticket numbers, using
 * SHA-256 (not a platform random generator, whose output can change between Java versions). The service stores the
 * {@link Outcome} (algorithm, seed, candidates, result) for audit; replaying {@code Draw.pick(seed, candidates)} gives
 * the same winner. The seed itself is chosen by the service when the draw is made (Phase 5) and stored before use.
 *
 * <p>Algorithm SHA256-MOD-V1: candidates are sorted ascending; the input text is
 * {@code CHIT-DRAW-V1|<seed>|<candidates joined by ,>|<purpose>|<counter>}; the first 8 bytes of its SHA-256 digest,
 * read as an unsigned number, modulo the number of remaining candidates picks an index. The modulo bias is below
 * n / 2^64, which is negligible for a chit.
 */
public final class Draw {

    public static final String ALGORITHM = "SHA256-MOD-V1";

    /** The audit record of one draw. {@code result} holds the winner (one entry) or a full order (FIXED_ORDER). */
    public record Outcome(String algorithm, long seed, List<Integer> candidates, List<Integer> result) {
    }

    private Draw() {
    }

    /** Picks one winner. */
    public static Outcome pick(long seed, List<Integer> candidates) {
        List<Integer> sorted = sortedCopy(candidates);
        int index = index(seed, sorted, "PICK", 0, sorted.size());
        return new Outcome(ALGORITHM, seed, sorted, List.of(sorted.get(index)));
    }

    /** Draws a complete order of all candidates (Fisher-Yates shuffle driven by the same digest), for FIXED_ORDER. */
    public static Outcome order(long seed, List<Integer> candidates) {
        List<Integer> sorted = sortedCopy(candidates);
        List<Integer> shuffled = new ArrayList<>(sorted);
        for (int i = shuffled.size() - 1; i >= 1; i--) {
            int j = index(seed, sorted, "ORDER", i, i + 1);
            Collections.swap(shuffled, i, j);
        }
        return new Outcome(ALGORITHM, seed, sorted, List.copyOf(shuffled));
    }

    private static List<Integer> sortedCopy(List<Integer> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new ChitException("NO_CANDIDATES", "A draw needs at least one candidate ticket");
        }
        List<Integer> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted);
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).equals(sorted.get(i - 1))) {
                throw new ChitException("DUPLICATE_TICKET", "Ticket " + sorted.get(i) + " appears twice");
            }
        }
        return sorted;
    }

    private static int index(long seed, List<Integer> sorted, String purpose, int counter, int bound) {
        StringBuilder text = new StringBuilder("CHIT-DRAW-V1|").append(seed).append('|');
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(sorted.get(i));
        }
        text.append('|').append(purpose).append('|').append(counter);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            BigInteger value = new BigInteger(1, java.util.Arrays.copyOfRange(digest, 0, 8));
            return value.mod(BigInteger.valueOf(bound)).intValueExact();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
