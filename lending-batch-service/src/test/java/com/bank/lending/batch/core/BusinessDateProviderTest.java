package com.bank.lending.batch.core;

import com.bank.lending.batch.lending.LendingServiceClient;
import com.bank.lending.batch.lending.LendingServiceSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Spring-free: where "today" comes from, and that the job context never falls back to the clock. */
class BusinessDateProviderTest {

    private static LendingServiceClient lendingReporting(String date) {
        return new LendingServiceClient(new LendingServiceSettings("http://unused", null, null, null, null, 1,
                Duration.ofMillis(1)), new ObjectMapper()) {
            @Override
            public BusinessDateInfo businessDate() {
                return new BusinessDateInfo(date, null, null, "OPEN", true, null);
            }
        };
    }

    @Test
    void byDefaultTheBusinessDateComesFromLendingService() {
        BusinessDateProvider p = new BusinessDateProvider(lendingReporting("2031-02-03"), "LENDING", "Asia/Kolkata");
        assertEquals(LocalDate.of(2031, 2, 3), p.current());
    }

    @Test
    void systemSourceUsesTheWallClockAndNeverCallsLending() {
        LendingServiceClient mustNotBeCalled = new LendingServiceClient(
                new LendingServiceSettings("http://unused", null, null, null, null, 1, Duration.ofMillis(1)),
                new ObjectMapper()) {
            @Override
            public BusinessDateInfo businessDate() {
                throw new AssertionError("lending-service must not be called for source=SYSTEM");
            }
        };
        BusinessDateProvider p = new BusinessDateProvider(mustNotBeCalled, "system", "Asia/Kolkata");
        assertEquals(LocalDate.now(ZoneId.of("Asia/Kolkata")), p.current());
    }

    @Test
    void theJobContextHasNoWallClockFallback() {
        BatchJobContext.clear();
        assertThrows(IllegalStateException.class, BatchJobContext::businessDate);
        BatchJobContext.set(LocalDate.of(2026, 10, 12), "{}");
        assertEquals(LocalDate.of(2026, 10, 12), BatchJobContext.businessDate());
        BatchJobContext.clear();
    }
}
