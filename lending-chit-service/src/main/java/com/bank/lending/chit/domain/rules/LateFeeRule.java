package com.bank.lending.chit.domain.rules;

import java.math.BigDecimal;

/**
 * How a late fee is charged once a payment is more than {@code graceDays} late.
 * <ul>
 *   <li>NONE: no late fee.</li>
 *   <li>FLAT: {@code flatAmount}, charged once per overdue demand.</li>
 *   <li>PERCENT_PER_DAY: {@code pctPerDay} percent of the overdue amount for each chargeable day.</li>
 * </ul>
 * A cap is an absolute amount ({@code capAmount}), a percentage of the overdue amount ({@code capPctOfOverdue}),
 * or both (the lower applies). PERCENT_PER_DAY must have at least one cap.
 */
public record LateFeeRule(Kind kind, BigDecimal flatAmount, BigDecimal pctPerDay,
                          BigDecimal capAmount, BigDecimal capPctOfOverdue) {

    public enum Kind { NONE, FLAT, PERCENT_PER_DAY }

    public static LateFeeRule none() {
        return new LateFeeRule(Kind.NONE, null, null, null, null);
    }

    public static LateFeeRule flat(BigDecimal amount) {
        return new LateFeeRule(Kind.FLAT, amount, null, null, null);
    }

    public static LateFeeRule percentPerDay(BigDecimal pctPerDay, BigDecimal capAmount, BigDecimal capPctOfOverdue) {
        return new LateFeeRule(Kind.PERCENT_PER_DAY, null, pctPerDay, capAmount, capPctOfOverdue);
    }
}
