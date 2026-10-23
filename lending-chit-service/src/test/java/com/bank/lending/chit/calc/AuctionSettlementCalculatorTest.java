package com.bank.lending.chit.calc;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.common.Money;
import com.bank.lending.chit.domain.LastCycleCommissionRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionSettlementCalculatorTest {

    private static final BigDecimal P = new BigDecimal("100000.00");
    private static final BigDecimal FIVE = new BigDecimal("5");

    private static List<Integer> tickets(int n) {
        List<Integer> l = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            l.add(i);
        }
        return l;
    }

    @Test
    void fixtureA_evenDividend() {
        AuctionSettlement s = AuctionSettlementCalculator.settle(P, new BigDecimal("20000.00"), FIVE, tickets(20));
        assertEquals(new BigDecimal("80000.00"), s.prize());
        assertEquals(new BigDecimal("5000.00"), s.commission());
        assertEquals(new BigDecimal("15000.00"), s.dividendPool());
        for (BigDecimal share : s.dividendShares().values()) {
            assertEquals(new BigDecimal("750.00"), share);
        }
        assertTrue(s.isBalanced());
        // next instalment per ticket = 5,000 - 750
        assertEquals(new BigDecimal("4250.00"), new BigDecimal("5000.00").subtract(s.dividendShares().get(1)));
    }

    @Test
    void fixtureB_unevenPool() {
        // discount 20,000.07 -> pool 15,000.07
        AuctionSettlement s = AuctionSettlementCalculator.settle(P, new BigDecimal("20000.07"), FIVE, tickets(20));
        assertEquals(new BigDecimal("15000.07"), s.dividendPool());
        assertEquals(new BigDecimal("750.01"), s.dividendShares().get(1));
        assertEquals(new BigDecimal("750.01"), s.dividendShares().get(7));
        assertEquals(new BigDecimal("750.00"), s.dividendShares().get(8));
        assertTrue(s.isBalanced());
    }

    @Test
    void fixtureD_lastCycleWithNoCommission() {
        AuctionSettlement s = AuctionSettlementCalculator.settleLastCycle(P, FIVE, LastCycleCommissionRule.NONE);
        assertEquals(new BigDecimal("100000.00"), s.prize());
        assertEquals(new BigDecimal("0.00"), s.commission());
        assertEquals(new BigDecimal("0.00"), s.dividendPool());
        assertTrue(s.dividendShares().isEmpty());
        assertTrue(s.lastCycle());
        assertTrue(s.isBalanced());
    }

    @Test
    void fixtureD_lastCycleWithCommissionDeductedFromPrize() {
        AuctionSettlement s = AuctionSettlementCalculator.settleLastCycle(P, FIVE, LastCycleCommissionRule.DEDUCT_FROM_PRIZE);
        assertEquals(new BigDecimal("95000.00"), s.prize());
        assertEquals(new BigDecimal("5000.00"), s.commission());
        assertTrue(s.isBalanced());
    }

    @Test
    void fixtureE_discountBelowCommissionIsRefused() {
        ChitException e = assertThrows(ChitException.class,
                () -> AuctionSettlementCalculator.settle(P, new BigDecimal("4999.99"), FIVE, tickets(20)));
        assertEquals("DISCOUNT_BELOW_COMMISSION", e.code());
    }

    @Test
    void discountEqualToCommissionGivesNoDividend() {
        AuctionSettlement s = AuctionSettlementCalculator.settle(P, new BigDecimal("5000.00"), FIVE, List.of());
        assertEquals(new BigDecimal("0.00"), s.dividendPool());
        assertEquals(new BigDecimal("95000.00"), s.prize());
        assertTrue(s.isBalanced());
    }

    @Test
    void discountOutsideTheChitValueIsRefused() {
        assertEquals("DISCOUNT_OUT_OF_RANGE", assertThrows(ChitException.class,
                () -> AuctionSettlementCalculator.settle(P, new BigDecimal("-1.00"), FIVE, tickets(2))).code());
        assertEquals("DISCOUNT_OUT_OF_RANGE", assertThrows(ChitException.class,
                () -> AuctionSettlementCalculator.settle(P, new BigDecimal("100000.01"), FIVE, tickets(2))).code());
    }

    @Test
    void aPoolWithNobodyToShareItIsRefused() {
        assertEquals("NO_DIVIDEND_BENEFICIARIES", assertThrows(ChitException.class,
                () -> AuctionSettlementCalculator.settle(P, new BigDecimal("20000.00"), FIVE, List.of())).code());
    }

    @Test
    void excludingDefaultersStillSharesTheWholePool() {
        // 4 of 20 tickets excluded (defaulters): the other 16 share the same 15,000.00
        List<Integer> entitled = new ArrayList<>(tickets(20));
        entitled.removeAll(List.of(3, 8, 9, 15));
        AuctionSettlement s = AuctionSettlementCalculator.settle(P, new BigDecimal("20000.00"), FIVE, entitled);
        assertEquals(16, s.dividendShares().size());
        assertEquals(new BigDecimal("937.50"), s.dividendShares().get(1));
        assertTrue(s.isBalanced());
    }

    @Test
    void propertyThePotIsAlwaysFullyAccountedFor() {
        Random random = new Random(1023L);
        for (int run = 0; run < 2000; run++) {
            BigDecimal pot = Money.ofPaise(100_000_00L + random.nextInt(9_000_000));
            BigDecimal pct = new BigDecimal(random.nextInt(8)); // 0..7 percent
            BigDecimal commission = Money.percentOf(pot, pct);
            BigDecimal discount = commission.add(Money.ofPaise(random.nextInt(3_000_000)));
            if (discount.compareTo(pot) > 0) {
                discount = pot;
            }
            int n = 1 + random.nextInt(30);
            AuctionSettlement s = AuctionSettlementCalculator.settle(pot, discount, pct, tickets(n));
            assertTrue(s.isBalanced(), "run " + run);
            assertEquals(pot, s.prize().add(s.commission()).add(s.dividendPool()), "gross identity, run " + run);
        }
    }
}
