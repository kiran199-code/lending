package com.bank.lending.chit.calc;

import java.math.BigDecimal;
import java.util.SortedMap;

/**
 * The money outcome of one cycle's auction.
 *
 * <p>Identity that always holds: {@code chitValue = prize + commission + dividendPool}. In an ordinary cycle
 * {@code prize = chitValue - discount} and {@code dividendPool = discount - commission}. In the last cycle there is no
 * discount; the commission is either not taken or deducted from the prize (see LastCycleCommissionRule).
 *
 * @param dividendShares ticket number to its share of the dividend pool; the shares add up to {@code dividendPool}
 *                       exactly. Empty when the pool is zero.
 */
public record AuctionSettlement(BigDecimal chitValue, BigDecimal discount, BigDecimal prize, BigDecimal commission,
                                BigDecimal dividendPool, SortedMap<Integer, BigDecimal> dividendShares,
                                boolean lastCycle) {

    /** The gross identity: the pot is fully accounted for. */
    public boolean isBalanced() {
        return chitValue.compareTo(prize.add(commission).add(dividendPool)) == 0
                && dividendShares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(dividendPool) == 0;
    }
}
