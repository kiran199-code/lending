package com.bank.lending.chit.calc;

import com.bank.lending.chit.common.Money;
import com.bank.lending.chit.domain.rules.LateFeeRule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Late fee, one day at a time. The fee for a day depends on what is overdue that day, so a part payment lowers
 * the fee from then on. Calendar days are counted (not business days). TODO-VERIFY: the real-world rule.
 *
 * <p>A day is chargeable when it is more than {@code graceDays} after the due date. {@link #accrualForDay} is the one
 * source of truth: the daily batch job calls it, and {@link #preview} repeats it for an unchanged overdue amount.
 * Passing the fee accrued so far makes a rerun and a catch-up (a skipped day) safe: nothing is charged twice and
 * the cap is never exceeded.
 */
public final class LateFeeCalculator {

    public record DailyAccrual(LocalDate date, BigDecimal accrual, BigDecimal cumulative) {
    }

    private LateFeeCalculator() {
    }

    /**
     * The fee for {@code day}.
     *
     * @param overdueAmount  what is overdue on that day
     * @param accruedSoFar   fee already accrued for this demand before {@code day}
     */
    public static BigDecimal accrualForDay(LateFeeRule rule, BigDecimal overdueAmount, LocalDate dueDate, LocalDate day,
                                           int graceDays, BigDecimal accruedSoFar) {
        BigDecimal soFar = Money.nz(accruedSoFar);
        long daysLate = ChronoUnit.DAYS.between(dueDate, day);
        if (rule.kind() == LateFeeRule.Kind.NONE || daysLate <= graceDays || overdueAmount.signum() <= 0) {
            return Money.ZERO;
        }
        BigDecimal wanted = switch (rule.kind()) {
            case FLAT -> soFar.signum() == 0 ? Money.scale(rule.flatAmount()) : Money.ZERO;
            case PERCENT_PER_DAY -> Money.percentOf(overdueAmount, rule.pctPerDay());
            case NONE -> Money.ZERO;
        };
        BigDecimal room = capRoom(rule, overdueAmount, soFar);
        return Money.scale(Money.min(wanted, room));
    }

    /** What the daily accrual would be for {@code days} days starting at {@code from}, with the overdue amount unchanged. */
    public static List<DailyAccrual> preview(LateFeeRule rule, BigDecimal overdueAmount, LocalDate dueDate, LocalDate from,
                                             int days, int graceDays, BigDecimal accruedBefore) {
        List<DailyAccrual> out = new ArrayList<>();
        BigDecimal cumulative = Money.nz(accruedBefore);
        for (int i = 0; i < days; i++) {
            LocalDate day = from.plusDays(i);
            BigDecimal accrual = accrualForDay(rule, overdueAmount, dueDate, day, graceDays, cumulative);
            cumulative = cumulative.add(accrual);
            out.add(new DailyAccrual(day, accrual, cumulative));
        }
        return out;
    }

    private static BigDecimal capRoom(LateFeeRule rule, BigDecimal overdueAmount, BigDecimal soFar) {
        BigDecimal cap = null;
        if (rule.capAmount() != null) {
            cap = Money.scale(rule.capAmount());
        }
        if (rule.capPctOfOverdue() != null) {
            BigDecimal byPct = Money.percentOf(overdueAmount, rule.capPctOfOverdue());
            cap = cap == null ? byPct : Money.min(cap, byPct);
        }
        if (cap == null) {
            return new BigDecimal("999999999999.99"); // no cap configured (FLAT without a cap)
        }
        return Money.max(Money.ZERO, cap.subtract(soFar));
    }
}
