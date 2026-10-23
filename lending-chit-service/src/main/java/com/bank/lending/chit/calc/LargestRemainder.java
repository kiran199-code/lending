package com.bank.lending.chit.calc;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.common.Money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Splits an amount of money among tickets so that the shares add up exactly to the total.
 *
 * <p>Method (largest remainder, exact integer arithmetic): every ticket's quota is {@code total x weight / sum of
 * weights} in paise; each gets the whole paise of its quota; the paise left over (always fewer than the number of
 * tickets) go one each to the tickets with the largest fractional remainder. When remainders tie, the lower ticket
 * number wins, so the result is deterministic and can be replayed. With equal weights this is the rule "the first
 * few tickets by number get one extra paisa".
 */
public final class LargestRemainder {

    private LargestRemainder() {
    }

    /** Equal shares among the given tickets. */
    public static SortedMap<Integer, BigDecimal> equalShares(BigDecimal total, List<Integer> ticketNos) {
        SortedMap<Integer, BigDecimal> weights = new TreeMap<>();
        for (Integer t : ticketNos) {
            if (weights.put(t, BigDecimal.ONE) != null) {
                throw new ChitException("DUPLICATE_TICKET", "Ticket " + t + " appears twice");
            }
        }
        return allocate(total, weights);
    }

    /**
     * @param total   amount to split, not negative, at most 2 decimal places
     * @param weights ticket number to weight (not negative; at least one above zero)
     * @return ticket number to share (scale 2); the shares add up to {@code total} exactly
     */
    public static SortedMap<Integer, BigDecimal> allocate(BigDecimal total, Map<Integer, BigDecimal> weights) {
        if (total.signum() < 0) {
            throw new ChitException("AMOUNT_NEGATIVE", "Cannot split a negative amount");
        }
        SortedMap<Integer, BigDecimal> result = new TreeMap<>();
        if (weights.isEmpty()) {
            if (total.signum() == 0) {
                return result;
            }
            throw new ChitException("NO_BENEFICIARIES", "There is nobody to share " + total.toPlainString() + " among");
        }
        int maxScale = 0;
        for (BigDecimal w : weights.values()) {
            if (w == null || w.signum() < 0) {
                throw new ChitException("WEIGHT_INVALID", "Weights must be zero or more");
            }
            maxScale = Math.max(maxScale, Math.max(w.scale(), 0));
        }
        List<Integer> keys = new ArrayList<>(new TreeMap<>(weights).keySet());
        BigInteger[] w = new BigInteger[keys.size()];
        BigInteger sum = BigInteger.ZERO;
        for (int i = 0; i < w.length; i++) {
            w[i] = weights.get(keys.get(i)).setScale(maxScale).unscaledValue();
            sum = sum.add(w[i]);
        }
        if (sum.signum() == 0) {
            if (total.signum() == 0) {
                for (Integer k : keys) {
                    result.put(k, Money.ZERO);
                }
                return result;
            }
            throw new ChitException("NO_BENEFICIARIES", "All weights are zero");
        }
        BigInteger totalPaise = BigInteger.valueOf(Money.toPaise(total));
        long[] floor = new long[w.length];
        BigInteger[] remainder = new BigInteger[w.length];
        long assigned = 0;
        for (int i = 0; i < w.length; i++) {
            BigInteger[] qr = totalPaise.multiply(w[i]).divideAndRemainder(sum);
            floor[i] = qr[0].longValueExact();
            remainder[i] = qr[1];
            assigned += floor[i];
        }
        long left = totalPaise.longValueExact() - assigned;
        // hand out the leftover paise, largest remainder first, lower ticket number first on a tie
        boolean[] given = new boolean[w.length];
        for (long n = 0; n < left; n++) {
            int best = -1;
            for (int i = 0; i < w.length; i++) {
                if (given[i]) {
                    continue;
                }
                if (best < 0 || remainder[i].compareTo(remainder[best]) > 0) {
                    best = i;
                }
            }
            given[best] = true;
            floor[best] += 1;
        }
        for (int i = 0; i < w.length; i++) {
            result.put(keys.get(i), Money.ofPaise(floor[i]));
        }
        return result;
    }
}
