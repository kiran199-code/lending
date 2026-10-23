package com.bank.lending.chit.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorStatusTest {

    @Test
    void knownCodesMapToTheirStatus() {
        assertEquals(400, ErrorStatus.of("ACTOR_REQUIRED"));
        assertEquals(400, ErrorStatus.of("INVALID_CURSOR"));
        assertEquals(400, ErrorStatus.of("CONFIRMATION_MISSING"));
        assertEquals(400, ErrorStatus.of("IDEMPOTENCY_KEY_REQUIRED"));
        assertEquals(403, ErrorStatus.of("RESET_DISABLED"));
        assertEquals(404, ErrorStatus.of("NOT_FOUND"));
        assertEquals(409, ErrorStatus.of("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(409, ErrorStatus.of("POSTING_KEY_REUSED"));
        assertEquals(409, ErrorStatus.of("ILLEGAL_TRANSITION"));
        assertEquals(409, ErrorStatus.of("RULES_FROZEN"));
    }

    @Test
    void businessRuleRefusalsAreUnprocessable() {
        assertEquals(422, ErrorStatus.of("CONFIG_INVALID"));
        assertEquals(422, ErrorStatus.of("DISCOUNT_BELOW_COMMISSION"));
        assertEquals(422, ErrorStatus.of("SOMETHING_NEW_NOBODY_LISTED"));
    }

    @Test
    void internalConsistencyFailuresAreServerErrors() {
        assertEquals(500, ErrorStatus.of("JOURNAL_UNBALANCED"));
        assertEquals(500, ErrorStatus.of("ACCOUNT_MISSING"));
        assertEquals(500, ErrorStatus.of("DIVIDEND_SHARES_MISMATCH"));
    }
}
