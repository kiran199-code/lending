package com.bank.lending.chit.ledger;

import com.bank.lending.chit.common.ChitException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A journal that has been built but not yet posted. Construction checks the same rules the database enforces:
 * at least two lines, every amount positive, and debits equal to credits. A zero-amount line is simply not added
 * (see {@link Builder#debit}), so a template never has to special-case an empty commission or dividend.
 *
 * @param template   the template name (DEMAND, PAYMENT, AUCTION_SETTLED, ...); stored with the journal
 * @param postingKey unique per business event; posting the same key twice is a no-op, never a second journal
 */
public record JournalDraft(String template, String postingKey, String narration, List<Line> lines) {

    public JournalDraft {
        if (template == null || template.isBlank()) {
            throw new ChitException("JOURNAL_INVALID", "Journal template is required");
        }
        if (postingKey == null || postingKey.isBlank()) {
            throw new ChitException("JOURNAL_INVALID", "Journal posting key is required");
        }
        if (postingKey.length() > 120) {
            throw new ChitException("JOURNAL_INVALID", "Journal posting key is longer than 120 characters");
        }
        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            throw new ChitException("JOURNAL_UNBALANCED", template + " needs at least two lines, has " + lines.size());
        }
        BigDecimal debits = total(lines, Side.DEBIT);
        BigDecimal credits = total(lines, Side.CREDIT);
        if (debits.compareTo(credits) != 0) {
            throw new ChitException("JOURNAL_UNBALANCED",
                    template + " is not balanced: debits " + debits.toPlainString() + ", credits " + credits.toPlainString());
        }
    }

    public BigDecimal total() {
        return total(lines, Side.DEBIT);
    }

    /** The mirror image of this journal: every line on the opposite side. Used to reverse a posting. */
    public JournalDraft reversal(String reversalKey, String narration) {
        List<Line> mirrored = new ArrayList<>();
        for (Line l : lines) {
            mirrored.add(new Line(l.account(), l.side().opposite(), l.amount()));
        }
        return new JournalDraft(template + "_REVERSAL", reversalKey, narration, mirrored);
    }

    private static BigDecimal total(List<Line> lines, Side side) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Line l : lines) {
            if (l.side() == side) {
                sum = sum.add(l.amount());
            }
        }
        return sum;
    }

    public static Builder builder(String template, String postingKey) {
        return new Builder(template, postingKey);
    }

    public static final class Builder {
        private final String template;
        private final String postingKey;
        private String narration;
        private final List<Line> lines = new ArrayList<>();

        private Builder(String template, String postingKey) {
            this.template = template;
            this.postingKey = postingKey;
        }

        public Builder narration(String text) {
            this.narration = text;
            return this;
        }

        /** Adds a debit line; a null or zero amount adds nothing. */
        public Builder debit(AccountRef account, BigDecimal amount) {
            if (amount != null && amount.signum() != 0) {
                lines.add(Line.debit(account, amount));
            }
            return this;
        }

        /** Adds a credit line; a null or zero amount adds nothing. */
        public Builder credit(AccountRef account, BigDecimal amount) {
            if (amount != null && amount.signum() != 0) {
                lines.add(Line.credit(account, amount));
            }
            return this;
        }

        public JournalDraft build() {
            return new JournalDraft(template, postingKey, narration, lines);
        }
    }
}
