package com.bank.lending.chit.ledger;

/**
 * The chart of accounts of one chit group. Names match the {@code ledger_account.account_type} check in V2.
 * {@code normalSide} is the side that increases the balance; {@code perTicket} accounts exist once per ticket.
 */
public enum AccountType {
    // assets
    POOL_CASH(Side.DEBIT, false),
    SECURITY_CASH(Side.DEBIT, false),
    FOREMAN_CASH(Side.DEBIT, false),
    SUBSCRIBER_RECEIVABLE(Side.DEBIT, true),
    FOREMAN_GUARANTEE_RECEIVABLE(Side.DEBIT, true),
    // the pool itself: what the group owes to the members' pot (credit when instalments are demanded)
    CHIT_POOL(Side.CREDIT, false),
    // liabilities
    PRIZE_PAYABLE(Side.CREDIT, false),
    DIVIDEND_PAYABLE(Side.CREDIT, true),
    SECURITY_DEPOSIT_LIABILITY(Side.CREDIT, true),
    FOREMAN_FLOAT_PAYABLE(Side.CREDIT, false),
    UNCLAIMED(Side.CREDIT, false),
    // income
    COMMISSION_INCOME(Side.CREDIT, false),
    LATE_FEE_INCOME(Side.CREDIT, false),
    ENROLMENT_FEE_INCOME(Side.CREDIT, false),
    TRANSFER_FEE_INCOME(Side.CREDIT, false),
    // losses
    WRITE_OFF_LOSS(Side.DEBIT, false),
    GUARANTEE_LOSS_EXPENSE(Side.DEBIT, false);

    private final Side normalSide;
    private final boolean perTicket;

    AccountType(Side normalSide, boolean perTicket) {
        this.normalSide = normalSide;
        this.perTicket = perTicket;
    }

    public Side normalSide() {
        return normalSide;
    }

    public boolean perTicket() {
        return perTicket;
    }
}
