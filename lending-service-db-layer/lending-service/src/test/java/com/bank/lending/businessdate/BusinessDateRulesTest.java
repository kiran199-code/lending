package com.bank.lending.businessdate;

import com.bank.lending.businessdate.domain.BusinessDateStatus;
import com.bank.lending.businessdate.service.BusinessDateRules;
import com.bank.lending.businessdate.service.BusinessDateRules.AdvanceDecision;
import com.bank.lending.common.exception.InvalidAccountStateException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spring-free: the business-date state machine and date checks. */
class BusinessDateRulesTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 12);

    @Test
    void nextDateIsTheFollowingCalendarDayEvenAcrossMonthEnd() {
        assertEquals(LocalDate.of(2026, 10, 13), BusinessDateRules.nextDate(D));
        assertEquals(LocalDate.of(2026, 11, 1), BusinessDateRules.nextDate(LocalDate.of(2026, 10, 31)));
    }

    @Test
    void endOfDayStartsOnlyFromOpen() {
        BusinessDateRules.checkStartEndOfDay(BusinessDateStatus.OPEN, D);
        assertThrows(InvalidAccountStateException.class,
                () -> BusinessDateRules.checkStartEndOfDay(BusinessDateStatus.EOD_RUNNING, D));
    }

    @Test
    void advanceNeedsEndOfDayRunningAndTheExactCurrentDate() {
        assertEquals(AdvanceDecision.ADVANCE, BusinessDateRules.checkAdvance(BusinessDateStatus.EOD_RUNNING, D, D));
        assertThrows(InvalidAccountStateException.class,
                () -> BusinessDateRules.checkAdvance(BusinessDateStatus.OPEN, D, D));
        assertThrows(InvalidAccountStateException.class,
                () -> BusinessDateRules.checkAdvance(BusinessDateStatus.EOD_RUNNING, D, D.plusDays(1)));
        assertThrows(IllegalArgumentException.class,
                () -> BusinessDateRules.checkAdvance(BusinessDateStatus.EOD_RUNNING, D, null));
    }

    @Test
    void aRetriedAdvanceForADateThatAlreadyClosedIsAcknowledgedNotRepeated() {
        assertEquals(AdvanceDecision.ALREADY_ADVANCED,
                BusinessDateRules.checkAdvance(BusinessDateStatus.OPEN, D.plusDays(1), D));
        assertEquals(AdvanceDecision.ALREADY_ADVANCED,
                BusinessDateRules.checkAdvance(BusinessDateStatus.EOD_RUNNING, D.plusDays(1), D));
    }

    @Test
    void setMovesForwardOnlyWhileOpen() {
        BusinessDateRules.checkSet(BusinessDateStatus.OPEN, D, D.plusDays(30), false);
        BusinessDateRules.checkSet(BusinessDateStatus.OPEN, D, D, false);
        assertThrows(InvalidAccountStateException.class,
                () -> BusinessDateRules.checkSet(BusinessDateStatus.OPEN, D, D.minusDays(1), false));
        BusinessDateRules.checkSet(BusinessDateStatus.OPEN, D, D.minusDays(1), true);
        assertThrows(InvalidAccountStateException.class,
                () -> BusinessDateRules.checkSet(BusinessDateStatus.EOD_RUNNING, D, D.plusDays(1), true));
    }

    @Test
    void transactionsCannotBeDatedAfterTheBusinessDate() {
        BusinessDateRules.checkNotAfter(D, D, "valueDate");
        BusinessDateRules.checkNotAfter(D.minusDays(5), D, "valueDate");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BusinessDateRules.checkNotAfter(D.plusDays(1), D, "valueDate"));
        assertTrue(e.getMessage().contains("valueDate"));
        assertTrue(e.getMessage().contains("2026-10-12"));
        assertThrows(IllegalArgumentException.class, () -> BusinessDateRules.checkNotAfter(null, D, "valueDate"));
    }

    @Test
    void workingDayExcludesWeekendAndHolidays() {
        Set<DayOfWeek> weekend = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        LocalDate monday = LocalDate.of(2026, 10, 12);
        assertTrue(BusinessDateRules.isWorkingDay(monday, weekend, Set.of()));
        assertFalse(BusinessDateRules.isWorkingDay(LocalDate.of(2026, 10, 10), weekend, Set.of())); // Saturday
        assertFalse(BusinessDateRules.isWorkingDay(LocalDate.of(2026, 10, 11), weekend, Set.of())); // Sunday
        assertFalse(BusinessDateRules.isWorkingDay(monday, weekend, Set.of(monday)));               // holiday
    }
}
