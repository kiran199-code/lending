package com.bank.lending.chit.ledger;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.ledger.LedgerDtos.AccountBalance;
import com.bank.lending.chit.ledger.LedgerDtos.JournalView;
import com.bank.lending.chit.ledger.LedgerDtos.PostResult;
import com.bank.lending.chit.ledger.LedgerDtos.PostingContext;
import com.bank.lending.chit.ledger.LedgerDtos.TrialBalance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Posts journals. Every business feature builds a {@link JournalDraft} with {@link JournalTemplates} and calls
 * {@link #post} inside its own transaction (MANDATORY: a journal is never posted on its own, so it commits or rolls
 * back together with the business change it records).
 *
 * <p>Posting is idempotent by posting key: posting the same key again with the same journal returns the existing
 * journal; the same key with a different journal is refused (POSTING_KEY_REUSED).
 */
@Service
public class LedgerService {

    private final LedgerRepository repo;

    public LedgerService(LedgerRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult post(JournalDraft draft, PostingContext ctx) {
        return postInternal(draft, ctx, null);
    }

    /**
     * Reverses a posted journal with its mirror image. A journal can be reversed once. The reversal's posting key is
     * the original key plus ":R", so a retry is a no-op.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult reverse(long journalId, String reason, PostingContext ctx) {
        JournalView original = repo.findJournal(journalId).orElseThrow(() -> ChitException.notFound("Journal", String.valueOf(journalId)));
        if (original.groupId() != ctx.groupId()) {
            throw ChitException.notFound("Journal", String.valueOf(journalId));
        }
        if (original.reversesJournalId() != null) {
            throw ChitException.conflict("ILLEGAL_TRANSITION", "Journal " + journalId + " is itself a reversal and cannot be reversed");
        }
        JournalDraft mirror = repo.loadDraft(journalId).reversal(original.postingKey() + ":R", reason);
        if (repo.isReversed(journalId)) {
            // already reversed: return the existing reversal when this is a retry of the same call
            Optional<LedgerRepository.Existing> existing = repo.findByPostingKey(mirror.postingKey());
            if (existing.isPresent()) {
                return new PostResult(existing.get().id(), false);
            }
            throw ChitException.conflict("ILLEGAL_TRANSITION", "Journal " + journalId + " has already been reversed");
        }
        return postInternal(mirror, ctx, journalId);
    }

    /**
     * Creates the whole chart of accounts of a group at activation: every group-level account, and the per-ticket
     * accounts for each ticket. Safe to call again (accounts that exist are left alone); postings would create the
     * accounts they need anyway, this just makes the set complete and visible from day one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int ensureGroupAccounts(long groupId, List<Long> ticketIds) {
        int count = 0;
        for (AccountType type : AccountType.values()) {
            if (type.perTicket()) {
                for (Long ticketId : ticketIds) {
                    repo.ensureAccount(groupId, AccountRef.of(type, ticketId));
                    count++;
                }
            } else {
                repo.ensureAccount(groupId, AccountRef.of(type));
                count++;
            }
        }
        return count;
    }

    @Transactional(readOnly = true)
    public TrialBalance trialBalance(long groupId) {
        List<AccountBalance> accounts = repo.balances(groupId);
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (AccountBalance a : accounts) {
            debits = debits.add(a.debits());
            credits = credits.add(a.credits());
        }
        return new TrialBalance(groupId, accounts, debits, credits, debits.compareTo(credits) == 0);
    }

    @Transactional(readOnly = true)
    public Optional<JournalView> journal(long journalId) {
        return repo.findJournal(journalId);
    }

    @Transactional(readOnly = true)
    public List<JournalView> journals(long groupId, long afterId, int limit) {
        return repo.journals(groupId, afterId, Math.max(1, Math.min(limit, 200)));
    }

    private PostResult postInternal(JournalDraft draft, PostingContext ctx, Long reversesJournalId) {
        // resolve (and lazily create) the accounts first, in a stable order, so concurrent postings take the same locks
        Map<AccountRef, Long> ids = new LinkedHashMap<>();
        draft.lines().stream().map(Line::account).distinct()
                .sorted(java.util.Comparator.comparing((AccountRef a) -> a.type().name())
                        .thenComparing(a -> a.ticketId() == null ? 0L : a.ticketId()))
                .forEach(a -> ids.put(a, repo.ensureAccount(ctx.groupId(), a)));

        Optional<Long> journalId = repo.insertJournal(draft, ctx, reversesJournalId);
        if (journalId.isEmpty()) {
            LedgerRepository.Existing existing = repo.findByPostingKey(draft.postingKey())
                    .orElseThrow(() -> ChitException.conflict("DATA_CONFLICT", "Posting key vanished: " + draft.postingKey()));
            if (existing.groupId() != ctx.groupId() || !existing.template().equals(draft.template())
                    || existing.total().compareTo(draft.total()) != 0) {
                throw ChitException.conflict("POSTING_KEY_REUSED",
                        "Posting key '" + draft.postingKey() + "' was already used for a different journal");
            }
            return new PostResult(existing.id(), false);
        }
        int no = 1;
        for (Line line : draft.lines()) {
            repo.insertLine(journalId.get(), no++, ids.get(line.account()), line);
        }
        return new PostResult(journalId.get(), true);
    }
}
