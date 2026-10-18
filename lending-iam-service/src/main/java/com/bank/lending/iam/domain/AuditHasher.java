package com.bank.lending.iam.domain;

import com.bank.lending.iam.common.Hashing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The hash chain of the audit trail. Each row's hash covers the previous row's hash and the row's own content,
 * so changing or removing any row breaks every hash after it.
 */
public final class AuditHasher {
    public static final String GENESIS = "0".repeat(64);
    private static final char SEP = '\u001f';

    private AuditHasher() {}

    /** The time is cut to microseconds because that is what the database stores. */
    public static Instant normalise(Instant t) {
        return t.truncatedTo(ChronoUnit.MICROS);
    }

    public record Row(long id, Instant occurredAt, String actorType, String actor, String action, String targetType,
                      String targetId, String outcome, String ip, String correlationId, String detail,
                      String prevHash, String rowHash) {
        public String computeHash() {
            return AuditHasher.hash(prevHash, occurredAt, actorType, actor, action, targetType, targetId, outcome, ip, correlationId, detail);
        }
    }

    public static String hash(String prevHash, Instant occurredAt, String actorType, String actor, String action,
                              String targetType, String targetId, String outcome, String ip, String correlationId, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append(prevHash).append(SEP)
                .append(normalise(occurredAt)).append(SEP)
                .append(nz(actorType)).append(SEP).append(nz(actor)).append(SEP).append(nz(action)).append(SEP)
                .append(nz(targetType)).append(SEP).append(nz(targetId)).append(SEP).append(nz(outcome)).append(SEP)
                .append(nz(ip)).append(SEP).append(nz(correlationId)).append(SEP).append(nz(detail));
        return Hashing.sha256Hex(sb.toString());
    }

    /**
     * @param ok            true when every row's hash matches its content and every row points at the one before
     * @param count         rows checked (up to and including the first broken row)
     * @param firstBrokenId id of the first row that does not verify, or null
     */
    public record Verification(boolean ok, long count, Long firstBrokenId, String reason) {}

    /**
     * Verifies rows in id order.
     * @param expectedFirstPrev the hash of the row just before the range, or null when the range starts at the
     *                          first row of the table (then the genesis value is expected) or the previous row is unknown
     */
    public static Verification verify(List<Row> rows, String expectedFirstPrev, boolean startsAtBeginning) {
        String expectedPrev = expectedFirstPrev != null ? expectedFirstPrev : (startsAtBeginning ? GENESIS : null);
        long n = 0;
        for (Row r : rows) {
            n++;
            if (expectedPrev != null && !expectedPrev.equals(r.prevHash())) {
                return new Verification(false, n, r.id(), "previous hash does not match the row before it (a row was removed, inserted or changed)");
            }
            if (!r.computeHash().equals(r.rowHash())) {
                return new Verification(false, n, r.id(), "row content does not match its hash (the row was changed)");
            }
            expectedPrev = r.rowHash();
        }
        return new Verification(true, n, null, null);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
