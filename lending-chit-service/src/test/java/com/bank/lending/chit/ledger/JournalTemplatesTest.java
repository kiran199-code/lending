package com.bank.lending.chit.ledger;

import com.bank.lending.chit.calc.AuctionSettlement;
import com.bank.lending.chit.calc.AuctionSettlementCalculator;
import com.bank.lending.chit.common.ChitException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.bank.lending.chit.ledger.AccountType.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalTemplatesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, d(expected).compareTo(actual), "expected " + expected + " but was " + actual.toPlainString());
    }

    @Test
    void demandThenPaymentClearsTheReceivable() {
        LedgerBalances b = new LedgerBalances()
                .apply(JournalTemplates.demand("D", 7, d("10000")))
                .apply(JournalTemplates.payment("P", 7, d("10000")));
        assertAmount("0", b.balance(SUBSCRIBER_RECEIVABLE, 7));
        assertAmount("10000", b.balance(POOL_CASH));
        assertAmount("10000", b.balance(CHIT_POOL));
    }

    @Test
    void auctionSettlementJournalBalancesForAnyDiscount() {
        List<Integer> nos = new ArrayList<>();
        Map<Integer, Long> ids = new TreeMap<>();
        for (int i = 1; i <= 20; i++) {
            nos.add(i);
            ids.put(i, 1000L + i);
        }
        // fixture B: a pool that does not divide evenly (15,000.07 over 20 tickets)
        AuctionSettlement s = AuctionSettlementCalculator.settle(d("200000.00"), d("25000.07"), d("5"), nos);
        assertAmount("15000.07", s.dividendPool());
        JournalDraft j = JournalTemplates.auctionSettled("A", s, JournalTemplates.dividendsByTicketId(s.dividendShares(), ids));
        assertAmount("200000.00", j.total());
        LedgerBalances b = new LedgerBalances().apply(j);
        assertAmount("-200000.00", b.balance(CHIT_POOL)); // debited, so negative on its credit side
        assertAmount("10000.00", b.balance(COMMISSION_INCOME));
        assertAmount("174999.93", b.balance(PRIZE_PAYABLE));
        assertAmount("15000.07", b.balanceAcrossTickets(DIVIDEND_PAYABLE));
        assertAmount("750.01", b.balance(DIVIDEND_PAYABLE, 1001));
        assertAmount("750.00", b.balance(DIVIDEND_PAYABLE, 1020));
    }

    @Test
    void auctionSettlementRefusesSharesThatDoNotMatchThePool() {
        AuctionSettlement s = AuctionSettlementCalculator.settle(d("100000.00"), d("10000.00"), d("5"), List.of(1, 2));
        Map<Long, BigDecimal> wrong = new TreeMap<>();
        wrong.put(1L, d("2500.00"));
        wrong.put(2L, d("2499.99"));
        ChitException e = assertThrows(ChitException.class, () -> JournalTemplates.auctionSettled("A", s, wrong));
        assertEquals("DIVIDEND_SHARES_MISMATCH", e.code());
    }

    @Test
    void dividendsByTicketIdNeedsEveryTicket() {
        TreeMap<Integer, BigDecimal> byNo = new TreeMap<>();
        byNo.put(1, d("1"));
        byNo.put(2, d("1"));
        ChitException e = assertThrows(ChitException.class,
                () -> JournalTemplates.dividendsByTicketId(byNo, Map.of(1, 11L)));
        assertEquals("TICKET_UNKNOWN", e.code());
    }

    @Test
    void zeroCommissionAndZeroDividendProduceTwoLines() {
        AuctionSettlement s = AuctionSettlementCalculator.settle(d("100000.00"), d("0.00"), d("0"), List.of(1));
        JournalDraft j = JournalTemplates.auctionSettled("A", s, Map.of());
        assertEquals(2, j.lines().size());
    }

    @Test
    void prizeWithAHoldKeepsTheHoldInTheGroup() {
        LedgerBalances b = new LedgerBalances()
                .apply(JournalTemplates.prizeDisbursed("PR", 5, d("170000"), d("10000")));
        assertAmount("-170000", b.balance(PRIZE_PAYABLE)); // the payable is cleared (it is debited here, as if it had been funded)
        assertAmount("-170000", b.balance(POOL_CASH));
        assertAmount("10000", b.balance(SECURITY_CASH));
        assertAmount("10000", b.balance(SECURITY_DEPOSIT_LIABILITY, 5));
    }

    @Test
    void holdAboveTheGrossIsRefused() {
        assertEquals("HOLD_INVALID", assertThrows(ChitException.class,
                () -> JournalTemplates.prizeDisbursed("PR", 5, d("100"), d("100.01"))).code());
        assertEquals("HOLD_INVALID", assertThrows(ChitException.class,
                () -> JournalTemplates.prizeDisbursed("PR", 5, d("100"), d("-1"))).code());
    }

    @Test
    void securityAppliedMovesDepositCashIntoThePool() {
        LedgerBalances b = new LedgerBalances()
                .apply(JournalTemplates.demand("D", 3, d("10000")))
                .apply(JournalTemplates.securityReceived("S", 3, d("10000")))
                .apply(JournalTemplates.securityApplied("SA", 3, d("10000")));
        assertAmount("0", b.balance(SUBSCRIBER_RECEIVABLE, 3));
        assertAmount("0", b.balance(SECURITY_DEPOSIT_LIABILITY, 3));
        assertAmount("0", b.balance(SECURITY_CASH));
        assertAmount("10000", b.balance(POOL_CASH));
    }

    @Test
    void foremanCoversThenTicketRepays() {
        LedgerBalances b = new LedgerBalances()
                .apply(JournalTemplates.demand("D", 3, d("10000")))
                .apply(JournalTemplates.foremanCovers("FC", 3, d("10000")));
        assertAmount("0", b.balance(SUBSCRIBER_RECEIVABLE, 3));
        assertAmount("10000", b.balance(FOREMAN_GUARANTEE_RECEIVABLE, 3));
        assertAmount("10000", b.balance(POOL_CASH));
        assertAmount("-10000", b.balance(FOREMAN_CASH));
        b.apply(JournalTemplates.guaranteeRepaid("GR", 3, d("10000")));
        assertAmount("0", b.balance(FOREMAN_GUARANTEE_RECEIVABLE, 3));
        assertAmount("0", b.balance(FOREMAN_CASH));
    }

    @Test
    void writeOffsClearTheirReceivables() {
        LedgerBalances b = new LedgerBalances()
                .apply(JournalTemplates.demand("D", 3, d("500")))
                .apply(JournalTemplates.receivableWrittenOff("W", 3, d("500")));
        assertAmount("0", b.balance(SUBSCRIBER_RECEIVABLE, 3));
        assertAmount("500", b.balance(WRITE_OFF_LOSS));

        LedgerBalances g = new LedgerBalances()
                .apply(JournalTemplates.foremanCovers("FC", 4, d("700")))
                .apply(JournalTemplates.guaranteeWrittenOff("GW", 4, d("700")));
        assertAmount("0", g.balance(FOREMAN_GUARANTEE_RECEIVABLE, 4));
        assertAmount("700", g.balance(GUARANTEE_LOSS_EXPENSE));
    }

    @Test
    void floatAndRepaymentNetToZero() {
        LedgerBalances b = new LedgerBalances()
                .apply(JournalTemplates.foremanFloat("F", d("30000")))
                .apply(JournalTemplates.floatRepaid("FR", d("30000")));
        assertAmount("0", b.balance(POOL_CASH));
        assertAmount("0", b.balance(FOREMAN_FLOAT_PAYABLE));
    }

    @Test
    void feesAndRefundsMirrorEachOther() {
        LedgerBalances b = new LedgerBalances()
                .apply(JournalTemplates.enrolmentFee("E", d("250")))
                .apply(JournalTemplates.enrolmentFeeRefund("ER", d("250")))
                .apply(JournalTemplates.securityReceived("S", 9, d("5000")))
                .apply(JournalTemplates.securityRefund("SR", 9, d("5000")))
                .apply(JournalTemplates.transferFee("T", d("100")));
        assertAmount("0", b.balance(ENROLMENT_FEE_INCOME));
        assertAmount("0", b.balance(SECURITY_DEPOSIT_LIABILITY, 9));
        assertAmount("0", b.balance(SECURITY_CASH));
        assertAmount("100", b.balance(TRANSFER_FEE_INCOME));
        assertAmount("100", b.balance(FOREMAN_CASH));
    }

    @Test
    void lateFeeThenPaymentCollectsIncome() {
        LedgerBalances b = new LedgerBalances()
                .apply(JournalTemplates.lateFee("L", 2, d("150")))
                .apply(JournalTemplates.payment("P", 2, d("150")));
        assertAmount("0", b.balance(SUBSCRIBER_RECEIVABLE, 2));
        assertAmount("150", b.balance(LATE_FEE_INCOME));
        assertAmount("150", b.balance(POOL_CASH));
    }

    @Test
    void dividendRoutesBalance() {
        LedgerBalances b = new LedgerBalances()
                .apply(JournalTemplates.dividendApplied("DA", 1, d("1000")))
                .apply(JournalTemplates.dividendPaidOut("DP", 2, d("1000")))
                .apply(JournalTemplates.dividendForfeited("DF", 3, d("1000")));
        assertTrue(b.inBalance());
        assertAmount("1000", b.balance(UNCLAIMED));
    }

    @Test
    void everyTemplateReturnsABalancedJournal() {
        // JournalDraft refuses to be built unbalanced, so merely constructing each one proves it.
        List<JournalDraft> all = List.of(
                JournalTemplates.demand("1", 1, d("1")), JournalTemplates.payment("2", 1, d("1")),
                JournalTemplates.lateFee("3", 1, d("1")), JournalTemplates.dividendApplied("4", 1, d("1")),
                JournalTemplates.dividendPaidOut("5", 1, d("1")), JournalTemplates.dividendForfeited("6", 1, d("1")),
                JournalTemplates.prizeDisbursed("7", 1, d("1"), d("0")), JournalTemplates.closureSweep("8", d("1")),
                JournalTemplates.enrolmentFee("9", d("1")), JournalTemplates.securityReceived("10", 1, d("1")),
                JournalTemplates.enrolmentFeeRefund("11", d("1")), JournalTemplates.securityRefund("12", 1, d("1")),
                JournalTemplates.transferFee("13", d("1")), JournalTemplates.securityApplied("14", 1, d("1")),
                JournalTemplates.foremanCovers("15", 1, d("1")), JournalTemplates.guaranteeRepaid("16", 1, d("1")),
                JournalTemplates.guaranteeWrittenOff("17", 1, d("1")), JournalTemplates.receivableWrittenOff("18", 1, d("1")),
                JournalTemplates.foremanFloat("19", d("1")), JournalTemplates.floatRepaid("20", d("1")));
        assertEquals(20, all.size());
        for (JournalDraft j : all) {
            assertTrue(new LedgerBalances().apply(j).inBalance(), j.template());
        }
    }
}
