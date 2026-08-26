package com.bank.lending.common.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Reducing-balance (diminishing balance) loan math shared by schedule generation
 * and rate-reset recalculation. All monthly-rest EMI math assumes the interest
 * rate stored on LoanAccount is an annual percentage (e.g. 8.5000 for 8.5%).
 */
public final class FinanceMath {

    private static final MathContext MC = new MathContext(20);

    private FinanceMath() {
    }

    /** Converts an annual percentage rate (e.g. 8.5000) to a monthly decimal rate (e.g. 0.00708333...). */
    public static BigDecimal monthlyRate(BigDecimal annualRatePercent) {
        return annualRatePercent.divide(BigDecimal.valueOf(1200), MC);
    }

    /**
     * Standard reducing-balance EMI formula:
     * EMI = P * r * (1+r)^n / ((1+r)^n - 1), falling back to a straight-line
     * P/n split when the rate is zero.
     */
    public static BigDecimal calculateEmi(BigDecimal principal, BigDecimal monthlyRate, int tenureMonths) {
        if (tenureMonths <= 0) {
            throw new IllegalArgumentException("tenureMonths must be positive");
        }
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(tenureMonths), 2, RoundingMode.HALF_UP);
        }
        BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
        BigDecimal factor = onePlusR.pow(tenureMonths, MC);
        BigDecimal numerator = principal.multiply(monthlyRate).multiply(factor, MC);
        BigDecimal denominator = factor.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }
}
