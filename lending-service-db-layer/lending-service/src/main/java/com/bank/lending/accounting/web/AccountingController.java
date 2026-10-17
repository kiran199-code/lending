package com.bank.lending.accounting.web;

import com.bank.lending.accounting.dto.AccountingDtos.EventView;
import com.bank.lending.accounting.dto.AccountingDtos.ExceptionRow;
import com.bank.lending.accounting.dto.AccountingDtos.GateStatus;
import com.bank.lending.accounting.dto.AccountingDtos.GlAccountView;
import com.bank.lending.accounting.dto.AccountingDtos.JournalSummary;
import com.bank.lending.accounting.dto.AccountingDtos.JournalView;
import com.bank.lending.accounting.dto.AccountingDtos.LedgerView;
import com.bank.lending.accounting.dto.AccountingDtos.OpeningBalanceResult;
import com.bank.lending.accounting.dto.AccountingDtos.PostResultView;
import com.bank.lending.accounting.dto.AccountingDtos.ReconciliationResult;
import com.bank.lending.accounting.dto.AccountingDtos.RouteView;
import com.bank.lending.accounting.dto.AccountingDtos.SimulationRequest;
import com.bank.lending.accounting.dto.AccountingDtos.SimulationResult;
import com.bank.lending.accounting.dto.AccountingDtos.TrialBalance;
import com.bank.lending.accounting.service.AccountingEventService;
import com.bank.lending.accounting.service.AccountingQueryService;
import com.bank.lending.accounting.service.BalanceSnapshotService;
import com.bank.lending.accounting.service.OpeningBalanceService;
import com.bank.lending.accounting.service.PostResult;
import com.bank.lending.accounting.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Read APIs, the simulator, exceptions, and the end-of-day operations of the accounting module. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Accounting", description = "Journals, balances, reconciliation and posting exceptions of the double-entry ledger")
public class AccountingController {

    private static final String ACTOR_HEADER = "X-Actor";

    private final AccountingQueryService query;
    private final AccountingEventService eventService;
    private final BalanceSnapshotService snapshotService;
    private final ReconciliationService reconciliationService;
    private final OpeningBalanceService openingBalanceService;

    // ------------------------------------------------------------------------------------------------ journals

    @Operation(summary = "Search journals by account, event, journal number or business date range (newest first)")
    @GetMapping("/api/v1/accounting/journals")
    public List<JournalSummary> journals(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String eventCode,
            @RequestParam(required = false) String journalNo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String branchCode,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return query.searchJournals(accountId, eventCode, journalNo, from, to, branchCode, limit, offset);
    }

    @Operation(summary = "One journal with its lines and, if reversed, the reversing journal")
    @GetMapping("/api/v1/accounting/journals/{journalId}")
    public JournalView journal(@PathVariable long journalId) {
        return query.journal(journalId);
    }

    @Operation(summary = "Journals of one loan account, newest first")
    @GetMapping("/api/v1/loan-accounts/{accountId}/journals")
    public List<JournalSummary> accountJournals(@PathVariable long accountId,
                                                @RequestParam(defaultValue = "100") int limit,
                                                @RequestParam(defaultValue = "0") int offset) {
        return query.searchJournals(accountId, null, null, null, null, null, limit, offset);
    }

    @Operation(summary = "Trial balance: debits, credits and net per GL account up to a date (default: the business date)")
    @GetMapping("/api/v1/accounting/trial-balance")
    public TrialBalance trialBalance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) Integer ledgerId,
            @RequestParam(required = false) String branchCode) {
        return query.trialBalance(asOf, ledgerId, branchCode);
    }

    // ------------------------------------------------------------------------------------------------ configuration (read)

    @Operation(summary = "Accounting events with their amount tags, switches and ledgers")
    @GetMapping("/api/v1/accounting/events")
    public List<EventView> events() {
        return query.events();
    }

    @Operation(summary = "Ledgers (internal, memo and external)")
    @GetMapping("/api/v1/accounting/ledgers")
    public List<LedgerView> ledgers() {
        return query.ledgers();
    }

    @Operation(summary = "Chart of accounts of one ledger")
    @GetMapping("/api/v1/accounting/ledgers/{ledgerId}/accounts")
    public List<GlAccountView> accounts(@PathVariable int ledgerId) {
        return query.accounts(ledgerId);
    }

    @Operation(summary = "Routes from accounting roles to GL accounts")
    @GetMapping("/api/v1/accounting/routes")
    public List<RouteView> routes(@RequestParam(required = false) Integer ledgerId,
                                  @RequestParam(required = false) String status) {
        return query.routes(ledgerId, status);
    }

    @Operation(summary = "Show the journal an event would produce, without posting it (a draft template can be tried)")
    @PostMapping("/api/v1/accounting/simulate")
    public SimulationResult simulate(@RequestBody SimulationRequest request) {
        return query.simulate(request);
    }

    // ------------------------------------------------------------------------------------------------ exceptions

    @Operation(summary = "Posting exceptions: events that could not be journalled and are waiting for a retry")
    @GetMapping("/api/v1/accounting/exceptions")
    public List<ExceptionRow> exceptions(@RequestParam(required = false, defaultValue = "OPEN") String status,
                                         @RequestParam(defaultValue = "100") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        return query.exceptions(status, limit, offset);
    }

    @Operation(summary = "How many exceptions are open, and how many of them hold up end of day")
    @GetMapping("/api/v1/accounting/exceptions/gate")
    public GateStatus gate() {
        return query.gate();
    }

    @Operation(summary = "Retry one open posting exception")
    @PostMapping("/api/v1/accounting/exceptions/{exceptionId}/retry")
    public PostResultView retry(@PathVariable long exceptionId,
                                @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        PostResult r = eventService.retryException(exceptionId, actor == null ? "SYSTEM" : actor);
        return new PostResultView(r.status().name(), r.journalId(), r.journalNo(), r.note());
    }

    @Operation(summary = "Waive an open posting exception without journalling it (a reason is required)")
    @PostMapping("/api/v1/accounting/exceptions/{exceptionId}/waive")
    public Map<String, Object> waive(@PathVariable long exceptionId, @RequestBody Map<String, String> body,
                                     @RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        eventService.waiveException(exceptionId, actor == null ? "SYSTEM" : actor, body == null ? null : body.get("reason"));
        return Map.of("exceptionId", exceptionId, "status", "WAIVED");
    }

    // ------------------------------------------------------------------------------------------------ end of day

    @Operation(summary = "Batch: write the daily balance of every GL account for a business date (idempotent)")
    @PostMapping("/api/v1/accounting/snapshot")
    public Map<String, Object> snapshot(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        int rows = snapshotService.snapshot(businessDate);
        return Map.of("businessDate", businessDate == null ? "current" : businessDate.toString(), "balancesWritten", rows);
    }

    @Operation(summary = "Run the reconciliation of the ledger to the loan sub-ledger and store the result")
    @PostMapping("/api/v1/accounting/reconciliation/run")
    public ReconciliationResult runReconciliation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return reconciliationService.run(businessDate);
    }

    @Operation(summary = "Result of the latest reconciliation run for a date (default: the business date)")
    @GetMapping("/api/v1/accounting/reconciliation")
    public ReconciliationResult reconciliation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return reconciliationService.latest(businessDate);
    }

    @Operation(summary = "Cutover: journal every existing account's position as its opening balance (idempotent)")
    @PostMapping("/api/v1/accounting/opening-balance")
    public OpeningBalanceResult openingBalance(@RequestHeader(value = ACTOR_HEADER, required = false) String actor) {
        return openingBalanceService.run(actor);
    }
}
