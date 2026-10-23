package com.bank.lending.chit.ledger;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Running balances of one group's accounts, built by applying journals. Pure and in memory: used by the tests and by
 * the reconciliation check, which compares these balances with the sums read back from the database.
 *
 * <p>A balance is positive when the account is on its normal side (a cash account with more debits than credits, a
 * payable with more credits than debits). The trial balance is checked on raw debit and credit totals instead.
 */
public final class LedgerBalances {

    private final Map<AccountRef, BigDecimal> debits = new LinkedHashMap<>();
    private final Map<AccountRef, BigDecimal> credits = new LinkedHashMap<>();

    public LedgerBalances apply(JournalDraft journal) {
        for (Line l : journal.lines()) {
            (l.side() == Side.DEBIT ? debits : credits).merge(l.account(), l.amount(), BigDecimal::add);
        }
        return this;
    }

    public BigDecimal debits(AccountRef account) {
        return debits.getOrDefault(account, BigDecimal.ZERO);
    }

    public BigDecimal credits(AccountRef account) {
        return credits.getOrDefault(account, BigDecimal.ZERO);
    }

    /** Positive on the account's normal side, negative on the other side. */
    public BigDecimal balance(AccountRef account) {
        BigDecimal net = debits(account).subtract(credits(account));
        return account.type().normalSide() == Side.DEBIT ? net : net.negate();
    }

    public BigDecimal balance(AccountType type) {
        return balance(AccountRef.of(type));
    }

    public BigDecimal balance(AccountType type, long ticketId) {
        return balance(AccountRef.of(type, ticketId));
    }

    /** Sum of the balances of every ticket-level account of this type. */
    public BigDecimal balanceAcrossTickets(AccountType type) {
        BigDecimal sum = BigDecimal.ZERO;
        for (AccountRef a : accounts()) {
            if (a.type() == type) {
                sum = sum.add(balance(a));
            }
        }
        return sum;
    }

    public BigDecimal totalDebits() {
        return debits.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal totalCredits() {
        return credits.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The trial balance: total debits equal total credits. Always true if every journal was balanced. */
    public boolean inBalance() {
        return totalDebits().compareTo(totalCredits()) == 0;
    }

    public java.util.Set<AccountRef> accounts() {
        java.util.Set<AccountRef> all = new java.util.LinkedHashSet<>(debits.keySet());
        all.addAll(credits.keySet());
        return all;
    }
}
