package com.bank.lending.chit.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class LedgerDtos {
    private LedgerDtos() {
    }

    /** Outcome of a posting. {@code created} is false when the posting key had already been used by the same journal. */
    public record PostResult(long journalId, boolean created) {
    }

    public record AccountBalance(long accountId, AccountType accountType, Long ticketId, BigDecimal debits, BigDecimal credits,
                                 BigDecimal balance) {
    }

    /** All accounts of a group with their totals. {@code inBalance} must always be true. */
    public record TrialBalance(long groupId, List<AccountBalance> accounts, BigDecimal totalDebits, BigDecimal totalCredits,
                               boolean inBalance) {
    }

    public record LineView(int lineNo, AccountType accountType, Long ticketId, String side, BigDecimal amount) {
    }

    public record JournalView(long id, long groupId, String template, String postingKey, Long cycleId, LocalDate businessDate,
                              String referenceType, String referenceId, String narration, Long reversesJournalId,
                              String createdBy, OffsetDateTime createdAt, List<LineView> lines) {
    }

    /** What a caller supplies besides the journal itself. */
    public record PostingContext(long groupId, LocalDate businessDate, Long cycleId, String referenceType, String referenceId,
                                 String actor) {
    }
}
