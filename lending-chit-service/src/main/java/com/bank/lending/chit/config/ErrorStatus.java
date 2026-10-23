package com.bank.lending.chit.config;

import java.util.Map;

/**
 * The one place that turns a business error code into an HTTP status. Plain ints, so it needs no Spring and is unit
 * tested. Codes not listed are business-rule refusals and answer 422. Internal-consistency codes (an unbalanced journal,
 * a missing account) mean a bug, not bad input, and answer 500.
 */
public final class ErrorStatus {

    private static final Map<String, Integer> BY_CODE = Map.ofEntries(
            // bad request
            Map.entry("ACTOR_REQUIRED", 400),
            Map.entry("INVALID_CURSOR", 400),
            Map.entry("CONFIRMATION_MISSING", 400),
            Map.entry("VALIDATION_FAILED", 400),
            Map.entry("HEADER_REQUIRED", 400),
            Map.entry("MALFORMED_BODY", 400),
            Map.entry("IDEMPOTENCY_KEY_REQUIRED", 400),
            // forbidden
            Map.entry("RESET_DISABLED", 403),
            // not found
            Map.entry("NOT_FOUND", 404),
            // conflict
            Map.entry("IDEMPOTENCY_KEY_REUSED", 409),
            Map.entry("POSTING_KEY_REUSED", 409),
            Map.entry("ILLEGAL_TRANSITION", 409),
            Map.entry("RULES_FROZEN", 409),
            Map.entry("DATA_CONFLICT", 409),
            // internal consistency: these should never come from user input
            Map.entry("JOURNAL_UNBALANCED", 500),
            Map.entry("JOURNAL_INVALID", 500),
            Map.entry("JOURNAL_LINE_INVALID", 500),
            Map.entry("ACCOUNT_INVALID", 500),
            Map.entry("ACCOUNT_MISSING", 500),
            Map.entry("DIVIDEND_SHARES_MISMATCH", 500),
            Map.entry("TICKET_UNKNOWN", 500));

    private ErrorStatus() {
    }

    public static int of(String code) {
        return BY_CODE.getOrDefault(code, 422);
    }
}
