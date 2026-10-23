package com.bank.lending.chit.ledger;

import com.bank.lending.chit.calc.AuctionSettlement;
import com.bank.lending.chit.calc.AuctionSettlementCalculator;
import com.bank.lending.chit.domain.LastCycleCommissionRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.bank.lending.chit.ledger.AccountType.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A whole chit, cycle by cycle, through the journal templates: 20 tickets, chit value 200,000, subscription 10,000,
 * commission 5%, dividend taken off the next instalment. Every subscriber pays in full and on time. After the last
 * cycle and the closure sweep the pool must net to nothing, and the foreman must hold exactly the commission.
 */
class TwentyCycleSimulationTest {

    private static final int N = 20;
    private static final BigDecimal P = new BigDecimal("200000.00");
    private static final BigDecimal S = new BigDecimal("10000.00");
    private static final BigDecimal PCT = new BigDecimal("5");

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual.toPlainString());
    }

    private static LedgerBalances run(BigDecimal discount, LastCycleCommissionRule lastRule, List<BigDecimal> cashAfterCycle) {
        LedgerBalances ledger = new LedgerBalances();
        List<Integer> nos = new ArrayList<>();
        Map<Integer, Long> ids = new TreeMap<>();
        for (int i = 1; i <= N; i++) {
            nos.add(i);
            ids.put(i, (long) i);
        }
        Map<Long, BigDecimal> carriedDividend = new TreeMap<>();
        for (int cycle = 1; cycle <= N; cycle++) {
            // 1. the instalment falls due for every ticket
            for (long t = 1; t <= N; t++) {
                ledger.apply(JournalTemplates.demand("DEMAND:" + cycle + ":" + t, t, S));
            }
            // 2. last cycle's dividend is set against this instalment, then the subscriber pays the rest
            for (long t = 1; t <= N; t++) {
                BigDecimal div = carriedDividend.getOrDefault(t, BigDecimal.ZERO);
                if (div.signum() > 0) {
                    ledger.apply(JournalTemplates.dividendApplied("DIVAPPLIED:" + cycle + ":" + t, t, div));
                }
                ledger.apply(JournalTemplates.payment("PAY:" + cycle + ":" + t, t, S.subtract(div)));
            }
            carriedDividend.clear();
            // 3. the auction settles the pot
            AuctionSettlement s = cycle < N
                    ? AuctionSettlementCalculator.settle(P, discount, PCT, nos)
                    : AuctionSettlementCalculator.settleLastCycle(P, PCT, lastRule);
            assertTrue(s.isBalanced());
            Map<Long, BigDecimal> byId = JournalTemplates.dividendsByTicketId(s.dividendShares(), ids);
            ledger.apply(JournalTemplates.auctionSettled("AUCTION:" + cycle, s, byId));
            carriedDividend.putAll(byId);
            // 4. the winner is paid
            ledger.apply(JournalTemplates.prizeDisbursed("PRIZE:" + cycle, cycle, s.prize(), BigDecimal.ZERO));
            cashAfterCycle.add(ledger.balance(POOL_CASH));
        }
        return ledger;
    }

    @Test
    void poolNetsToZeroAfterTheSweepWhenTheLastCycleTakesNoCommission() {
        List<BigDecimal> cash = new ArrayList<>();
        LedgerBalances ledger = run(new BigDecimal("30000.00"), LastCycleCommissionRule.NONE, cash);

        // cash in the pool is never negative at any point: no payout was ever made from money that was not there
        for (BigDecimal c : cash) {
            assertTrue(c.signum() >= 0, "pool cash went negative: " + c);
        }
        assertAmount("30000", cash.get(0));    // cycle 1: 200,000 in, 170,000 out
        assertAmount("40000", cash.get(1));    // cycle 2: 180,000 in (dividend off), 170,000 out
        assertAmount("190000", cash.get(N - 1)); // the 19 commissions: 19 x 10,000

        assertAmount("0", ledger.balance(CHIT_POOL));
        assertAmount("0", ledger.balance(PRIZE_PAYABLE));
        assertAmount("0", ledger.balanceAcrossTickets(SUBSCRIBER_RECEIVABLE));
        assertAmount("0", ledger.balanceAcrossTickets(DIVIDEND_PAYABLE));
        assertAmount("190000", ledger.balance(COMMISSION_INCOME));

        ledger.apply(JournalTemplates.closureSweep("SWEEP", ledger.balance(POOL_CASH)));
        assertAmount("0", ledger.balance(POOL_CASH));
        assertAmount("190000", ledger.balance(FOREMAN_CASH));
        assertTrue(ledger.inBalance());
    }

    @Test
    void foremanHoldsAllTwentyCommissionsWhenTheLastCycleDeductsIt() {
        List<BigDecimal> cash = new ArrayList<>();
        LedgerBalances ledger = run(new BigDecimal("30000.00"), LastCycleCommissionRule.DEDUCT_FROM_PRIZE, cash);
        assertAmount("200000", ledger.balance(COMMISSION_INCOME));
        ledger.apply(JournalTemplates.closureSweep("SWEEP", ledger.balance(POOL_CASH)));
        assertAmount("0", ledger.balance(POOL_CASH));
        assertAmount("200000", ledger.balance(FOREMAN_CASH));
        assertAmount("0", ledger.balance(CHIT_POOL));
    }

    @Test
    void anUnevenDividendStillNetsToZero() {
        // discount 25,000.07 leaves a pool of 15,000.07, which does not divide by 20: some tickets get a paisa more
        List<BigDecimal> cash = new ArrayList<>();
        LedgerBalances ledger = run(new BigDecimal("25000.07"), LastCycleCommissionRule.NONE, cash);
        assertAmount("0", ledger.balance(CHIT_POOL));
        assertAmount("0", ledger.balanceAcrossTickets(SUBSCRIBER_RECEIVABLE));
        assertAmount("0", ledger.balanceAcrossTickets(DIVIDEND_PAYABLE));
        for (BigDecimal c : cash) {
            assertTrue(c.signum() >= 0, "pool cash went negative: " + c);
        }
        ledger.apply(JournalTemplates.closureSweep("SWEEP", ledger.balance(POOL_CASH)));
        assertAmount("0", ledger.balance(POOL_CASH));
        assertAmount("190000", ledger.balance(FOREMAN_CASH));
    }
}
