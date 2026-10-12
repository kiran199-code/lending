package com.bank.lending.businessdate.service;

import com.bank.lending.businessdate.domain.BusinessDateStatus;
import com.bank.lending.common.exception.InvalidAccountStateException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * The business-date state machine and checks as pure functions (no Spring, no database), so the
 * rules can be tested directly. {@link BusinessDateService} applies them under a row lock.
 *
 * <pre>
 *   OPEN --startEndOfDay--> EOD_RUNNING --advance--> OPEN (next day)
 * </pre>
 */
public final class BusinessDateRules {

    private BusinessDateRules() {
    }

    public enum AdvanceDecision {
        /** Move to the next day. */
        ADVANCE,
        /** The caller's expected date is already behind us (a retried call): report current state, change nothing. */
        ALREADY_ADVANCED
    }

    public static LocalDate nextDate(LocalDate current) {
        return current.plusDays(1);
    }

    /** End of day may start only from OPEN; starting it again while it is running is a 409. */
    public static void checkStartEndOfDay(BusinessDateStatus status, LocalDate current) {
        if (status != BusinessDateStatus.OPEN) {
            throw new InvalidAccountStateException(
                    "End of day for " + current + " is already running; finish it (advance) before starting another");
        }
    }

    /**
     * Advance is idempotent on {@code expected}: if the business date is already past it, a retry
     * is acknowledged without moving again. Otherwise the caller must be advancing exactly the
     * current date and EOD must have been started.
     */
    public static AdvanceDecision checkAdvance(BusinessDateStatus status, LocalDate current, LocalDate expected) {
        if (expected == null) {
            throw new IllegalArgumentException("expectedBusinessDate is required");
        }
        if (current.isAfter(expected)) {
            return AdvanceDecision.ALREADY_ADVANCED;
        }
        if (!current.equals(expected)) {
            throw new InvalidAccountStateException(
                    "Business date is " + current + ", cannot advance from " + expected);
        }
        if (status != BusinessDateStatus.EOD_RUNNING) {
            throw new InvalidAccountStateException(
                    "Cannot advance " + current + ": end of day has not been started");
        }
        return AdvanceDecision.ADVANCE;
    }

    /** Setting the date is for initialisation and testing: only while OPEN, and forward only unless explicitly allowed. */
    public static void checkSet(BusinessDateStatus status, LocalDate current, LocalDate target, boolean allowBackward) {
        if (target == null) {
            throw new IllegalArgumentException("date is required");
        }
        if (status != BusinessDateStatus.OPEN) {
            throw new InvalidAccountStateException("Cannot set the business date while end of day is running");
        }
        if (target.isBefore(current) && !allowBackward) {
            throw new InvalidAccountStateException("Business date is " + current
                    + "; moving it back to " + target + " is not allowed (business-date.allow-backward-set=false)");
        }
    }

    /** Transactions must not be dated after the business date (no future-dating). */
    public static void checkNotAfter(LocalDate date, LocalDate businessDate, String what) {
        if (date == null) {
            throw new IllegalArgumentException(what + " is required");
        }
        if (date.isAfter(businessDate)) {
            throw new IllegalArgumentException(what + " " + date + " is after the current business date " + businessDate);
        }
    }

    public static boolean isWorkingDay(LocalDate date, Set<DayOfWeek> weekend, Set<LocalDate> holidays) {
        return !weekend.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }
}
