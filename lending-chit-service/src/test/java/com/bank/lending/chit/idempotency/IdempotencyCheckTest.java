package com.bank.lending.chit.idempotency;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.idempotency.IdempotencyCheck.Replay;
import com.bank.lending.chit.idempotency.IdempotencyCheck.Stored;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdempotencyCheckTest {

    @Test
    void sameOperationAndHashIsAReplayWithTheEarlierResult() {
        Replay r = IdempotencyCheck.decide("k", new Stored("PAYMENT", "h1", "PAY-7"), "PAYMENT", "h1");
        assertEquals("PAY-7", r.resultRef());
    }

    @Test
    void aReplayBeforeTheFirstCallFinishedHasNoResultYet() {
        assertNull(IdempotencyCheck.decide("k", new Stored("PAYMENT", "h1", null), "PAYMENT", "h1").resultRef());
    }

    @Test
    void aDifferentBodyOnTheSameKeyIsRefused() {
        ChitException e = assertThrows(ChitException.class,
                () -> IdempotencyCheck.decide("k", new Stored("PAYMENT", "h1", "PAY-7"), "PAYMENT", "h2"));
        assertEquals("IDEMPOTENCY_KEY_REUSED", e.code());
    }

    @Test
    void aDifferentOperationOnTheSameKeyIsRefused() {
        ChitException e = assertThrows(ChitException.class,
                () -> IdempotencyCheck.decide("k", new Stored("PAYMENT", "h1", "PAY-7"), "ENROL", "h1"));
        assertEquals("IDEMPOTENCY_KEY_REUSED", e.code());
    }
}
