package com.bank.lending.demand.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies money to what is owed, in a fixed order: penal interest first (oldest demand first), then each demand
 * oldest first with its interest before its principal. Whatever is left over is the surplus; the caller decides
 * whether it becomes an advance or a principal prepayment.
 *
 * <p>Plain Java on purpose: no Spring, no database, so the same code serves the real posting, the preview shown
 * before a payment is confirmed, and the unit tests.
 */
public final class PaymentWaterfall {

    private PaymentWaterfall() {
    }

    public enum Component { PENALTY, INTEREST, PRINCIPAL }

    /** What is still owed on one demand (or on an instalment that is due but not yet raised as a demand). */
    public record Due(Long demandId, Integer installmentNumber, LocalDate dueDate,
                      BigDecimal penalty, BigDecimal interest, BigDecimal principal) {
        public Due {
            penalty = penalty == null ? BigDecimal.ZERO : penalty;
            interest = interest == null ? BigDecimal.ZERO : interest;
            principal = principal == null ? BigDecimal.ZERO : principal;
        }

        public BigDecimal total() {
            return penalty.add(interest).add(principal);
        }
    }

    public record Line(Long demandId, Integer installmentNumber, Component component, BigDecimal amount) {
    }

    public record Result(List<Line> lines, BigDecimal surplus) {
        public BigDecimal applied(Component component) {
            return lines.stream().filter(l -> l.component() == component)
                    .map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal totalApplied() {
            return lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /**
     * @param dues   what is owed, oldest first (the caller sorts)
     * @param amount money to apply, greater than zero
     */
    public static Result apply(List<Due> dues, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        List<Line> lines = new ArrayList<>();
        BigDecimal left = amount;
        for (Due d : dues) {
            left = take(lines, d, Component.PENALTY, d.penalty(), left);
        }
        for (Due d : dues) {
            left = take(lines, d, Component.INTEREST, d.interest(), left);
            left = take(lines, d, Component.PRINCIPAL, d.principal(), left);
        }
        return new Result(List.copyOf(lines), left);
    }

    private static BigDecimal take(List<Line> lines, Due due, Component component, BigDecimal owed, BigDecimal left) {
        if (left.signum() <= 0 || owed.signum() <= 0) {
            return left;
        }
        BigDecimal pay = owed.min(left);
        lines.add(new Line(due.demandId(), due.installmentNumber(), component, pay));
        return left.subtract(pay);
    }
}
