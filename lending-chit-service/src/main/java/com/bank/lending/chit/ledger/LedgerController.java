package com.bank.lending.chit.ledger;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.ledger.LedgerDtos.JournalView;
import com.bank.lending.chit.ledger.LedgerDtos.TrialBalance;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only ledger views. Postings come only from the business features, never from the API. */
@RestController
@RequestMapping("/api/v1/chit/ledger")
@Tag(name = "Ledger", description = "Journals and trial balance of a chit group")
public class LedgerController {

    private final LedgerService service;

    public LedgerController(LedgerService service) {
        this.service = service;
    }

    @GetMapping("/groups/{groupId}/trial-balance")
    @Operation(summary = "Debit and credit totals per account; inBalance must be true (chit.ledger.read)")
    public TrialBalance trialBalance(@PathVariable long groupId) {
        return service.trialBalance(groupId);
    }

    @GetMapping("/groups/{groupId}/journals")
    @Operation(summary = "Journals of a group with their lines, oldest first; pass the last id as afterId to page (chit.ledger.read)")
    public List<JournalView> journals(@PathVariable long groupId,
                                      @RequestParam(value = "afterId", defaultValue = "0") long afterId,
                                      @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return service.journals(groupId, afterId, limit);
    }

    @GetMapping("/journals/{journalId}")
    @Operation(summary = "One journal with its lines (chit.ledger.read)")
    public JournalView journal(@PathVariable long journalId) {
        return service.journal(journalId).orElseThrow(() -> ChitException.notFound("Journal", String.valueOf(journalId)));
    }
}
