package com.bank.lending.chit.calc;

import com.bank.lending.chit.common.Money;

import java.math.BigDecimal;

/**
 * The most a ticket can sensibly pay now: everything owed on raised demands, plus the instalments not yet raised,
 * less advance already held. Anything above that cannot be matched to any instalment of this group and is refused
 * instead of sitting on the ledger as an unexplained credit (it is almost always a mistyped amount).
 *
 * <p>Dividends not yet applied are ignored, which makes the limit slightly generous, never too tight.
 */
public final class ObligationLimit {

    private ObligationLimit() {
    }

    public static BigDecimal maxAcceptable(BigDecimal outstandingOnRaisedDemands, int unraisedCycles, BigDecimal subscription,
                                           BigDecimal advanceHeld) {
        BigDecimal future = Money.scale(subscription).multiply(BigDecimal.valueOf(Math.max(0, unraisedCycles)));
        return Money.max(Money.ZERO, Money.nz(outstandingOnRaisedDemands).add(future).subtract(Money.nz(advanceHeld)));
    }
}
