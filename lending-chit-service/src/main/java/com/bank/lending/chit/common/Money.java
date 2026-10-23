package com.bank.lending.chit.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money helpers. One rounding mode for the whole service: HALF_UP at 2 decimal places (the same as the other
 * lending services). Amounts are always BigDecimal; never floating point. A percentage is written as 5 for 5%.
 */
public final class Money {

    public static final BigDecimal ZERO = new BigDecimal("0.00");
    public static final BigDecimal HUNDRED = new BigDecimal("100");
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Money() {
    }

    public static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(2, ROUNDING);
    }

    public static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    /** {@code amount x pct / 100}, rounded once at the end. */
    public static BigDecimal percentOf(BigDecimal amount, BigDecimal pct) {
        return amount.multiply(pct).divide(HUNDRED, 2, ROUNDING);
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** Amount in paise. Fails (ArithmeticException) if the value has more than 2 decimal places. */
    public static long toPaise(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    public static BigDecimal ofPaise(long paise) {
        return BigDecimal.valueOf(paise, 2);
    }

    public static boolean isPositive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }
}
