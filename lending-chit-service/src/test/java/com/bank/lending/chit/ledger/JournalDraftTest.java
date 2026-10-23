package com.bank.lending.chit.ledger;

import com.bank.lending.chit.common.ChitException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalDraftTest {

    private static final AccountRef CASH = AccountRef.of(AccountType.POOL_CASH);
    private static final AccountRef POOL = AccountRef.of(AccountType.CHIT_POOL);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void balancedJournalIsAccepted() {
        JournalDraft j = JournalDraft.builder("T", "K1").debit(CASH, d("100.00")).credit(POOL, d("100.00")).build();
        assertEquals(2, j.lines().size());
        assertEquals(0, d("100.00").compareTo(j.total()));
    }

    @Test
    void unbalancedJournalIsRefused() {
        ChitException e = assertThrows(ChitException.class,
                () -> JournalDraft.builder("T", "K1").debit(CASH, d("100.00")).credit(POOL, d("99.99")).build());
        assertEquals("JOURNAL_UNBALANCED", e.code());
    }

    @Test
    void oneSidedJournalIsRefused() {
        ChitException e = assertThrows(ChitException.class,
                () -> JournalDraft.builder("T", "K1").debit(CASH, d("100.00")).build());
        assertEquals("JOURNAL_UNBALANCED", e.code());
    }

    @Test
    void zeroAmountLinesAreDroppedButNegativeOnesAreRefused() {
        JournalDraft j = JournalDraft.builder("T", "K1").debit(CASH, d("5")).credit(POOL, d("5"))
                .credit(AccountRef.of(AccountType.COMMISSION_INCOME), BigDecimal.ZERO).build();
        assertEquals(2, j.lines().size());
        ChitException e = assertThrows(ChitException.class,
                () -> JournalDraft.builder("T", "K1").debit(CASH, d("-5")).credit(POOL, d("-5")).build());
        assertEquals("JOURNAL_LINE_INVALID", e.code());
    }

    @Test
    void moreThanTwoDecimalsIsRefused() {
        ChitException e = assertThrows(ChitException.class, () -> Line.debit(CASH, d("1.005")));
        assertEquals("JOURNAL_LINE_INVALID", e.code());
    }

    @Test
    void amountsAreNormalisedToTwoDecimals() {
        assertEquals(d("10.00"), Line.debit(CASH, d("10")).amount());
    }

    @Test
    void postingKeyIsRequiredAndBounded() {
        assertEquals("JOURNAL_INVALID", assertThrows(ChitException.class,
                () -> JournalDraft.builder("T", " ").debit(CASH, d("1")).credit(POOL, d("1")).build()).code());
        assertEquals("JOURNAL_INVALID", assertThrows(ChitException.class,
                () -> JournalDraft.builder("T", "x".repeat(121)).debit(CASH, d("1")).credit(POOL, d("1")).build()).code());
    }

    @Test
    void reversalMirrorsEveryLineAndStaysBalanced() {
        JournalDraft j = JournalDraft.builder("DEMAND", "K1").debit(CASH, d("70")).credit(POOL, d("70")).build();
        JournalDraft r = j.reversal("K1:R", "undo");
        assertEquals("DEMAND_REVERSAL", r.template());
        assertEquals(Side.CREDIT, r.lines().get(0).side());
        assertEquals(Side.DEBIT, r.lines().get(1).side());
        LedgerBalances b = new LedgerBalances().apply(j).apply(r);
        assertEquals(0, BigDecimal.ZERO.compareTo(b.balance(AccountType.POOL_CASH)));
        assertEquals(0, BigDecimal.ZERO.compareTo(b.balance(AccountType.CHIT_POOL)));
    }

    @Test
    void perTicketAccountsNeedATicketAndGroupAccountsMustNotHaveOne() {
        assertEquals("ACCOUNT_INVALID", assertThrows(ChitException.class,
                () -> AccountRef.of(AccountType.SUBSCRIBER_RECEIVABLE)).code());
        assertEquals("ACCOUNT_INVALID", assertThrows(ChitException.class,
                () -> AccountRef.of(AccountType.POOL_CASH, 3L)).code());
    }

    @Test
    void balancesFollowTheNormalSide() {
        LedgerBalances b = new LedgerBalances().apply(
                JournalDraft.builder("T", "K").debit(CASH, d("40")).credit(POOL, d("40")).build());
        assertEquals(0, d("40").compareTo(b.balance(AccountType.POOL_CASH)));
        assertEquals(0, d("40").compareTo(b.balance(AccountType.CHIT_POOL)));
        assertTrue(b.inBalance());
        assertFalse(b.accounts().isEmpty());
    }

    @Test
    void sideCodesMatchTheDatabase() {
        assertEquals('D', Side.DEBIT.code());
        assertEquals('C', Side.CREDIT.code());
        assertEquals(Side.CREDIT, Side.ofCode('C'));
    }
}
