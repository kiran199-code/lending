package com.bank.lending.chit.ledger;

import com.bank.lending.chit.common.ChitException;

import java.math.BigDecimal;

/** One line of a journal. The amount is always positive; the side says whether it is a debit or a credit. */
public record Line(AccountRef account, Side side, BigDecimal amount) {

    public Line {
        if (account == null || side == null || amount == null) {
            throw new ChitException("JOURNAL_LINE_INVALID", "A journal line needs an account, a side and an amount");
        }
        if (amount.signum() <= 0) {
            throw new ChitException("JOURNAL_LINE_INVALID", "A journal line amount must be positive: " + amount.toPlainString());
        }
        if (amount.scale() > 2) {
            throw new ChitException("JOURNAL_LINE_INVALID", "A journal line amount has more than 2 decimals: " + amount.toPlainString());
        }
        amount = amount.setScale(2);
    }

    public static Line debit(AccountRef account, BigDecimal amount) {
        return new Line(account, Side.DEBIT, amount);
    }

    public static Line credit(AccountRef account, BigDecimal amount) {
        return new Line(account, Side.CREDIT, amount);
    }
}
