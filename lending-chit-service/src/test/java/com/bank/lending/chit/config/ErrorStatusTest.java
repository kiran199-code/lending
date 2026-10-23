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
        assertEquals(503, ErrorStatus.of("BUSINESS_DATE_UNAVAILABLE"));
        assertEquals(404, ErrorStatus.of("NOT_FOUND"));
        assertEquals(409, ErrorStatus.of("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(409, ErrorStatus.of("POSTING_KEY_REUSED"));
        assertEquals(409, ErrorStatus.of("ILLEGAL_TRANSITION"));
        assertEquals(409, ErrorStatus.of("RULES_FROZEN"));
        assertEquals(403, ErrorStatus.of("MAKER_CHECKER_VIOLATION"));
        for (String code : new String[]{"GROUP_CODE_EXISTS", "GROUP_FULL", "ENROLMENT_NOT_OPEN", "WITHDRAWAL_NOT_ALLOWED",
                "RULES_LOCKED_BY_ENROLMENT", "ACTIVATION_BLOCKED"}) {
            assertEquals(409, ErrorStatus.of(code), code);
        }
    }

    @Test
    void businessRuleRefusalsAreUnprocessable() {
        assertEquals(422, ErrorStatus.of("CONFIG_INVALID"));
        assertEquals(422, ErrorStatus.of("DISCOUNT_BELOW_COMMISSION"));
        assertEquals(422, ErrorStatus.of("ENROLMENT_AMOUNT_MISMATCH"));
        assertEquals(422, ErrorStatus.of("GUARANTOR_REQUIRED"));
        assertEquals(422, ErrorStatus.of("CALENDAR_OVERLAP"));
        assertEquals(422, ErrorStatus.of("SOMETHING_NEW_NOBODY_LISTED"));
    }

    @Test
    void internalConsistencyFailuresAreServerErrors() {
        assertEquals(500, ErrorStatus.of("JOURNAL_UNBALANCED"));
        assertEquals(500, ErrorStatus.of("ACCOUNT_MISSING"));
        assertEquals(500, ErrorStatus.of("DIVIDEND_SHARES_MISMATCH"));
    }
}
