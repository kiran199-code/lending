package com.bank.lending.schedule.util;

import com.bank.lending.account.domain.LoanAccount;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;

/**
 * Daily interest accrual under the loan account's day count convention.
 * Note: 30/360 is conventionally used for period-to-period interest calculations
 * rather than single-day accrual; here it is simplified to a flat 360-day annual
 * basis for the daily batch job. Revisit with Treasury/Product if a stricter
 * 30/360 date-pair convention is required for any product.
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
            case THIRTY_360 -> BigDecimal.valueOf(360);
        };
    }
}
