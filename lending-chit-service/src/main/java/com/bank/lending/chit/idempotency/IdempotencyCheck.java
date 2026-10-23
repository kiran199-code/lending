package com.bank.lending.chit.idempotency;

import com.bank.lending.chit.common.ChitException;

/**
 * The decision half of idempotency, without the database: given what is stored under a key and what the caller sent
 * now, is this a replay of the same request or a different request reusing the key?
 */
public final class IdempotencyCheck {

    /** What the key's first use recorded. {@code resultRef} is null until the operation completed. */
    public record Stored(String operation, String requestHash, String resultRef) {
    }

    /** The outcome for a key seen before: the same request again, with the reference it produced (may be null). */
    public record Replay(String resultRef) {
    }

    private IdempotencyCheck() {
    }

    public static Replay decide(String key, Stored stored, String operation, String requestHash) {
        if (!stored.operation().equals(operation) || !stored.requestHash().equals(requestHash)) {
            throw ChitException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency key '" + key + "' was already used for a different request");
        }
        return new Replay(stored.resultRef());
    }
}
