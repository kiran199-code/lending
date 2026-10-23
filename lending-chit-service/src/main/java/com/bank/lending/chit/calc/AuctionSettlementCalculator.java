package com.bank.lending.chit.calc;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.common.Money;
import com.bank.lending.chit.domain.LastCycleCommissionRule;

import java.math.BigDecimal;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Turns an auction result into money: prize, commission and the dividend shares.
 *
 * <p>{@code D = discount}, {@code W = P - D}, {@code C = commissionPct x P}, {@code DP = D - C}. The dividend pool is
 * shared by {@link LargestRemainder} so the shares add up to {@code DP} exactly. The caller decides who the
 * beneficiaries are (all tickets, only non-prized ones, without defaulters); this class only splits the money.
 */
public final class AuctionSettlementCalculator {

    private AuctionSettlementCalculator() {
    }

    /**
     * An ordinary cycle.
     *
     * @param beneficiaryTicketNos tickets that share the dividend; may be empty only when the pool is zero
     * @throws ChitException DISCOUNT_OUT_OF_RANGE, DISCOUNT_BELOW_COMMISSION (the pool would be negative),
     *                       NO_DIVIDEND_BENEFICIARIES
     */
    public static AuctionSettlement settle(BigDecimal chitValue, BigDecimal discount, BigDecimal commissionPct,
                                           List<Integer> beneficiaryTicketNos) {
        BigDecimal p = Money.scale(chitValue);
        BigDecimal d = Money.scale(discount);
        if (d.signum() < 0 || d.compareTo(p) > 0) {
            throw new ChitException("DISCOUNT_OUT_OF_RANGE",
                    "The discount " + d.toPlainString() + " must be between 0 and the chit value " + p.toPlainString());
        }
        BigDecimal c = Money.percentOf(p, commissionPct);
        if (d.compareTo(c) < 0) {
            throw new ChitException("DISCOUNT_BELOW_COMMISSION",
                    "The discount " + d.toPlainString() + " is below the commission " + c.toPlainString()
                            + "; the dividend pool would be negative");
        }
        BigDecimal pool = d.subtract(c);
        SortedMap<Integer, BigDecimal> shares = shares(pool, beneficiaryTicketNos);
        return new AuctionSettlement(p, d, p.subtract(d), c, pool, shares, false);
    }

    /**
     * The last cycle: one ticket left, no auction, no discount. The winner gets the full pot, less the commission when
     * the rule is DEDUCT_FROM_PRIZE. There is no dividend.
     */
    public static AuctionSettlement settleLastCycle(BigDecimal chitValue, BigDecimal commissionPct,
                                                    LastCycleCommissionRule rule) {
        BigDecimal p = Money.scale(chitValue);
        BigDecimal c = rule == LastCycleCommissionRule.DEDUCT_FROM_PRIZE ? Money.percentOf(p, commissionPct) : Money.ZERO;
        return new AuctionSettlement(p, Money.ZERO, p.subtract(c), c, Money.ZERO, new TreeMap<>(), true);
    }

    private static SortedMap<Integer, BigDecimal> shares(BigDecimal pool, List<Integer> beneficiaries) {
        if (pool.signum() == 0) {
            return new TreeMap<>();
        }
        if (beneficiaries == null || beneficiaries.isEmpty()) {
            throw new ChitException("NO_DIVIDEND_BENEFICIARIES",
                    "The dividend pool is " + pool.toPlainString() + " but no ticket is entitled to it");
        }
        return LargestRemainder.equalShares(pool, beneficiaries);
    }
}
