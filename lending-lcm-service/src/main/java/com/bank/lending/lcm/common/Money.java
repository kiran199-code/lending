package com.bank.lending.lcm.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    public static final BigDecimal ZERO = new BigDecimal("0.00");
    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private Money() {}

    public static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    public static BigDecimal max0(BigDecimal v) {
        return v.signum() < 0 ? ZERO : v;
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
