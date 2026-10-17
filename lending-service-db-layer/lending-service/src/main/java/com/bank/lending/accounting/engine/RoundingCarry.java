package com.bank.lending.accounting.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Accruals are held at 4 decimal places but journals are posted at currency precision. To stop rounding drifting,
 * the amount posted on a day is the change in the <em>rounded cumulative</em> accrual:
 * {@code round(cumulative) - cumulativePosted}. After any number of days the posted total equals the rounded
 * cumulative, with no residual.
 */
public final class RoundingCarry {

    private RoundingCarry() {
    }

    public record Step(BigDecimal cumulativeAccrued, BigDecimal cumulativePosted, BigDecimal delta) {
    }

    public static Step apply(BigDecimal cumulativeAccrued, BigDecimal cumulativePosted, BigDecimal raw, int precision) {
        BigDecimal cum = cumulativeAccrued.add(raw);
        BigDecimal target = cum.setScale(precision, RoundingMode.HALF_UP);
        BigDecimal delta = target.subtract(cumulativePosted.setScale(precision, RoundingMode.HALF_UP));
        return new Step(cum, target, delta);
    }
}
