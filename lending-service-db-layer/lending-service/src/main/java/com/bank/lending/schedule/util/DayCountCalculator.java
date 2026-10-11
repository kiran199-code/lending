package com.bank.lending.schedule.util;

import com.bank.lending.account.domain.LoanAccount;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Interest under the loan account's day count convention.
 *
 * <ul>
 *   <li>{@link #dailyInterest} - one day of accrual for the daily batch job. The 30/360 family is
 *       simplified to a flat 360-day basis there, because a single day has no 30/360 date pair.</li>
 *   <li>{@link #yearFraction} - the exact fraction of a year between two dates, used to price a whole
 *       instalment period when the repayment schedule is built.</li>
 * </ul>
 */
public final class DayCountCalculator {

    private static final MathContext MC = new MathContext(20);

    private DayCountCalculator() {
    }

    public static BigDecimal dailyInterest(BigDecimal outstandingPrincipal,
                                            BigDecimal annualRatePercent,
                                            LoanAccount.DayCountConvention convention,
                                            LocalDate businessDate) {
        BigDecimal basis = yearBasis(convention, businessDate);
        BigDecimal rate = annualRatePercent.divide(BigDecimal.valueOf(100), MC);
        return outstandingPrincipal.multiply(rate, MC)
                .divide(basis, MC)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal yearBasis(LoanAccount.DayCountConvention convention, LocalDate businessDate) {
        return switch (convention) {
            case ACT_365 -> BigDecimal.valueOf(365);
            case ACT_ACT -> BigDecimal.valueOf(Year.isLeap(businessDate.getYear()) ? 366 : 365);
            case ACT_360, THIRTY_360, THIRTY_E_360 -> BigDecimal.valueOf(360);
        };
    }

    /** Fraction of a year from {@code start} (inclusive) to {@code end} (exclusive) under the convention. */
    public static BigDecimal yearFraction(LocalDate start, LocalDate end,
                                           LoanAccount.DayCountConvention convention) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end " + end + " is before start " + start);
        }
        return switch (convention) {
            case ACT_365 -> fraction(ChronoUnit.DAYS.between(start, end), 365);
            case ACT_360 -> fraction(ChronoUnit.DAYS.between(start, end), 360);
            case ACT_ACT -> actActIsda(start, end);
            case THIRTY_360 -> fraction(thirtyUsDays(start, end), 360);
            case THIRTY_E_360 -> fraction(thirtyEuroDays(start, end), 360);
        };
    }

    private static int yearLength(int year) {
        return Year.isLeap(year) ? 366 : 365;
    }

    private static BigDecimal fraction(long days, int basis) {
        return BigDecimal.valueOf(days).divide(BigDecimal.valueOf(basis), MC);
    }

    /** ACT/ACT (ISDA): each calendar year's days are divided by that year's length (365 or 366). */
    private static BigDecimal actActIsda(LocalDate start, LocalDate end) {
        if (start.getYear() == end.getYear()) {
            return fraction(ChronoUnit.DAYS.between(start, end), yearLength(start.getYear()));
        }
        BigDecimal total = BigDecimal.ZERO;
        LocalDate cursor = start;
        while (cursor.getYear() < end.getYear()) {
            LocalDate nextJan1 = LocalDate.of(cursor.getYear() + 1, 1, 1);
            total = total.add(fraction(ChronoUnit.DAYS.between(cursor, nextJan1), yearLength(cursor.getYear())));
            cursor = nextJan1;
        }
        return total.add(fraction(ChronoUnit.DAYS.between(cursor, end), yearLength(end.getYear())));
    }

    /** 30/360 US (bond basis, with the end-of-February rules). */
    static long thirtyUsDays(LocalDate start, LocalDate end) {
        int d1 = start.getDayOfMonth();
        int d2 = end.getDayOfMonth();
        boolean startIsFebEnd = isLastDayOfFebruary(start);
        boolean endIsFebEnd = isLastDayOfFebruary(end);
        if (startIsFebEnd && endIsFebEnd) {
            d2 = 30;
        }
        if (startIsFebEnd) {
            d1 = 30;
        }
        if (d2 == 31 && d1 >= 30) {
            d2 = 30;
        }
        if (d1 == 31) {
            d1 = 30;
        }
        return days360(start, end, d1, d2);
    }

    /** 30E/360 (Eurobond basis): any 31st counts as the 30th. */
    static long thirtyEuroDays(LocalDate start, LocalDate end) {
        return days360(start, end, Math.min(start.getDayOfMonth(), 30), Math.min(end.getDayOfMonth(), 30));
    }

    private static long days360(LocalDate start, LocalDate end, int d1, int d2) {
        return 360L * (end.getYear() - start.getYear())
                + 30L * (end.getMonthValue() - start.getMonthValue())
                + (d2 - d1);
    }

    private static boolean isLastDayOfFebruary(LocalDate date) {
        return date.getMonthValue() == 2 && date.getDayOfMonth() == YearMonth.from(date).lengthOfMonth();
    }
}
