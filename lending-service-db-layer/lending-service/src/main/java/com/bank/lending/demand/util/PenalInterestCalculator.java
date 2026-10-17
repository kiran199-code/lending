package com.bank.lending.demand.util;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.schedule.util.DayCountCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out the daily penal interest of one demand. Plain Java (no Spring, no database) so the nightly job, the
 * catch-up when a payment arrives, the amount-payable screen and the unit tests all use the same arithmetic.
 *
 * <p>Rules: a day is charged when it is later than the due date plus grace days. The charge for a day is
 * {@code base x annual rate / days in the year} (the account's day-count convention), held to 4 decimals, where the
 * base is the principal and interest of the demand still unpaid at the close of that day, i.e. after payments dated
 * that day. Penal interest never forms part of the base, so it never earns penal interest. An optional cap limits the
 * total charged on the demand to a percentage of its principal plus interest.
 */
public final class PenalInterestCalculator {

    private PenalInterestCalculator() {
    }

    /** The product's penal terms. {@code capPercent} is null for no cap. */
    public record Settings(boolean enabled, BigDecimal annualRatePercent, int graceDays, BigDecimal capPercent) {
        public static final Settings OFF = new Settings(false, BigDecimal.ZERO, 0, null);
    }

    /** Principal or interest paid on the demand, with the date the payment takes effect. */
    public record Paid(LocalDate valueDate, BigDecimal amount) {
    }

    /** One day's charge: the base it was charged on and the amount (4 decimals). */
    public record Accrual(LocalDate date, BigDecimal base, BigDecimal amount) {
    }

    /**
     * The days still to be charged on a demand, from the day after the last charged date (or the first chargeable day)
     * up to and including {@code through}. Empty when penal interest is off, the rate is zero, or nothing is due.
     *
     * @param dueDate        the demand's due date
     * @param amountDue      principal due plus interest due of the demand
     * @param paid           principal and interest payments on the demand (any order)
     * @param alreadyCharged penal interest charged on the demand so far (for the cap)
     * @param lastCharged    the last date already charged, or null if none
     */
    public static List<Accrual> accruals(LocalDate dueDate, BigDecimal amountDue, List<Paid> paid,
                                         BigDecimal alreadyCharged, LocalDate lastCharged, LocalDate through,
                                         Settings settings, LoanAccount.DayCountConvention convention) {
        List<Accrual> out = new ArrayList<>();
        if (settings == null || !settings.enabled() || settings.annualRatePercent().signum() <= 0) {
            return out;
        }
        LocalDate day = dueDate.plusDays(settings.graceDays() + 1L);
        if (lastCharged != null && !lastCharged.plusDays(1).isBefore(day)) {
            day = lastCharged.plusDays(1);
        }
        BigDecimal cap = settings.capPercent() == null ? null
                : amountDue.multiply(settings.capPercent()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        BigDecimal charged = alreadyCharged == null ? BigDecimal.ZERO : alreadyCharged;

        for (; !day.isAfter(through); day = day.plusDays(1)) {
            BigDecimal base = amountDue;
            for (Paid p : paid) {
                if (!p.valueDate().isAfter(day)) {
                    base = base.subtract(p.amount());
                }
            }
            if (base.signum() <= 0) {
                continue;
            }
            BigDecimal amount = DayCountCalculator.dailyInterest(base, settings.annualRatePercent(), convention, day);
            if (cap != null) {
                amount = amount.min(cap.subtract(charged));
            }
            if (amount.signum() <= 0) {
                if (cap != null) {
                    break; // the cap is used up
                }
                continue;
            }
            out.add(new Accrual(day, base.setScale(2, RoundingMode.HALF_UP), amount));
            charged = charged.add(amount);
        }
        return out;
    }

    /** Total of the given days' charges, to 4 decimals. */
    public static BigDecimal total(List<Accrual> accruals) {
        return accruals.stream().map(Accrual::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
