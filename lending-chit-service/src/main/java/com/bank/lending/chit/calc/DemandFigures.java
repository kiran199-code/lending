package com.bank.lending.chit.calc;

import com.bank.lending.chit.common.Money;

import java.math.BigDecimal;

/**
 * The money on one demand and what is still owed on it. Pure arithmetic, shared by payment, reversal, late fees and
 * the statements so that "paid in full" means the same thing everywhere (and the same thing as the database check
 * {@code ck_demand_paid_settled}).
 *
 * @param subscriptionAmount  the instalment demanded
 * @param dividendAdjustment  part of it already met by a dividend (never more than the instalment)
 * @param subscriptionPaid    received against the subscription
 * @param lateFeeAccrued      late fee charged so far
 * @param lateFeePaid         received against late fees
 */
public record DemandFigures(BigDecimal subscriptionAmount, BigDecimal dividendAdjustment, BigDecimal subscriptionPaid,
                            BigDecimal lateFeeAccrued, BigDecimal lateFeePaid) {

    public DemandFigures {
        subscriptionAmount = Money.nz(subscriptionAmount);
        dividendAdjustment = Money.nz(dividendAdjustment);
        subscriptionPaid = Money.nz(subscriptionPaid);
        lateFeeAccrued = Money.nz(lateFeeAccrued);
        lateFeePaid = Money.nz(lateFeePaid);
    }

    /** What the subscriber has to pay towards the instalment: the amount less the dividend adjustment. */
    public BigDecimal subscriptionDue() {
        return subscriptionAmount.subtract(dividendAdjustment);
    }

    public BigDecimal subscriptionOutstanding() {
        return Money.max(Money.ZERO, subscriptionDue().subtract(subscriptionPaid));
    }

    public BigDecimal lateFeeOutstanding() {
        return Money.max(Money.ZERO, lateFeeAccrued.subtract(lateFeePaid));
    }

    public BigDecimal totalOutstanding() {
        return subscriptionOutstanding().add(lateFeeOutstanding());
    }

    /** Nothing left to pay on this demand, fees included. */
    public boolean settled() {
        return subscriptionOutstanding().signum() == 0 && lateFeeOutstanding().signum() == 0;
    }

    public DemandFigures paySubscription(BigDecimal amount) {
        return new DemandFigures(subscriptionAmount, dividendAdjustment, subscriptionPaid.add(amount), lateFeeAccrued, lateFeePaid);
    }

    public DemandFigures payLateFee(BigDecimal amount) {
        return new DemandFigures(subscriptionAmount, dividendAdjustment, subscriptionPaid, lateFeeAccrued, lateFeePaid.add(amount));
    }

    public DemandFigures withDividendAdjustment(BigDecimal adjustment) {
        return new DemandFigures(subscriptionAmount, adjustment, subscriptionPaid, lateFeeAccrued, lateFeePaid);
    }

    public DemandFigures accrueLateFee(BigDecimal amount) {
        return new DemandFigures(subscriptionAmount, dividendAdjustment, subscriptionPaid, lateFeeAccrued.add(amount), lateFeePaid);
    }
}
