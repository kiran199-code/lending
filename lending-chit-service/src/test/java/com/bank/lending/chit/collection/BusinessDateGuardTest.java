package com.bank.lending.chit.collection;

import com.bank.lending.chit.common.ChitException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessDateGuardTest {

    private static final LocalDate PLATFORM = LocalDate.of(2026, 10, 24);

    @Test
    void noRequestedDateMeansThePlatformDate() {
        assertEquals(PLATFORM, BusinessDateGuard.resolve(null, PLATFORM));
        assertEquals(PLATFORM, BusinessDateGuard.resolve("  ", PLATFORM));
    }

    @Test
    void theSameDayIsAccepted() {
        assertEquals(PLATFORM, BusinessDateGuard.resolve("2026-10-24", PLATFORM));
        assertEquals(PLATFORM, BusinessDateGuard.resolve(" 2026-10-24 ", PLATFORM));
    }

    @Test
    void anotherDayIsRefusedWhetherEarlierOrLater() {
        assertEquals("BUSINESS_DATE_MISMATCH", assertThrows(ChitException.class, () -> BusinessDateGuard.resolve("2026-10-23", PLATFORM)).code());
        assertEquals("BUSINESS_DATE_MISMATCH", assertThrows(ChitException.class, () -> BusinessDateGuard.resolve("2026-10-25", PLATFORM)).code());
    }

    @Test
    void anUnreadableDateIsABadRequest() {
        assertEquals("VALIDATION_FAILED", assertThrows(ChitException.class, () -> BusinessDateGuard.resolve("24/10/2026", PLATFORM)).code());
    }
}
