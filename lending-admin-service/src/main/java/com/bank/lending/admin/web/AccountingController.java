package com.bank.lending.admin.web;

import com.bank.lending.admin.service.LendingAccountingClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Accounting screens: journal explorer, trial balance, posting exceptions and the reconciliation. Reads and the
 * three actions pass through to lending-service; the checks here only catch a malformed filter so the user gets a
 * clear message, and lending-service applies the real rules.
 */
@RestController
@RequestMapping("/api/admin/accounting")
@Tag(name = "Accounting", description = "Journals, trial balance, posting exceptions and reconciliation (read from lending-service)")
public class AccountingController {

    static final int MAX_PAGE = 500;
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");
    private static final Pattern STATUS = Pattern.compile("OPEN|RESOLVED|WAIVED|ALL");

    private final LendingAccountingClient client;

    public AccountingController(LendingAccountingClient client) {
        this.client = client;
    }

    @Operation(summary = "Search journals by account, event, journal number and business-date range (newest first)")
    @GetMapping("/journals")
    public JsonNode journals(@RequestParam(required = false) Long accountId, @RequestParam(required = false) String eventCode,
                             @RequestParam(required = false) String journalNo, @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to, @RequestParam(required = false) String branchCode,
                             @RequestParam(defaultValue = "50") int limit,
                             @RequestParam(defaultValue = "0") int offset) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("accountId", accountId);
        q.put("eventCode", code(eventCode, "eventCode"));
        q.put("journalNo", code(journalNo, "journalNo"));
        q.put("from", date(from, "from"));
        q.put("to", date(to, "to"));
        q.put("branchCode", code(branchCode, "branchCode"));
        q.put("limit", Math.max(1, Math.min(limit, MAX_PAGE)));
        q.put("offset", Math.max(0, offset));
        return client.get("/journals", q);
    }

    @Operation(summary = "One journal with its lines and, if it was reversed, the reversal")
    @GetMapping("/journals/{journalId}")
    public JsonNode journal(@PathVariable long journalId) {
        return client.get("/journals/" + journalId, Map.of());
    }

    @Operation(summary = "Debits, credits and net per GL account and branch up to a date (default: the business date); optionally one branch")
    @GetMapping("/trial-balance")
    public JsonNode trialBalance(@RequestParam(required = false) String asOf, @RequestParam(required = false) Integer ledgerId,
                                 @RequestParam(required = false) String branchCode) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("asOf", date(asOf, "asOf"));
        q.put("ledgerId", ledgerId);
        q.put("branchCode", code(branchCode, "branchCode"));
        return client.get("/trial-balance", q);
    }

    @Operation(summary = "Currencies accounts can be opened in (read-only; added through lending-service)")
    @GetMapping("/currencies")
    public JsonNode currencies() {
        return client.reference("/currencies");
    }

    @Operation(summary = "Branches accounts can be booked at (read-only; added through lending-service)")
    @GetMapping("/branches")
    public JsonNode branches() {
        return client.reference("/branches");
    }

    @Operation(summary = "The ledgers (internal primary, memo, external mirror)")
    @GetMapping("/ledgers")
    public JsonNode ledgers() {
        return client.get("/ledgers", Map.of());
    }

    @Operation(summary = "Posting exceptions: events that could not be journalled (status OPEN, RESOLVED, WAIVED or ALL)")
    @GetMapping("/exceptions")
    public JsonNode exceptions(@RequestParam(defaultValue = "OPEN") String status, @RequestParam(defaultValue = "100") int limit,
                               @RequestParam(defaultValue = "0") int offset) {
        if (!STATUS.matcher(status).matches()) {
            throw new IllegalArgumentException("status must be OPEN, RESOLVED, WAIVED or ALL");
        }
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("status", status);
        q.put("limit", Math.max(1, Math.min(limit, MAX_PAGE)));
        q.put("offset", Math.max(0, offset));
        return client.get("/exceptions", q);
    }

    @Operation(summary = "How many exceptions are open, and how many of them hold up end of day")
    @GetMapping("/exceptions/gate")
    public JsonNode gate() {
        return client.get("/exceptions/gate", Map.of());
    }

    @Operation(summary = "Retry one open posting exception")
    @PostMapping("/exceptions/{exceptionId}/retry")
    public JsonNode retry(@PathVariable long exceptionId, Principal user) {
        return client.post("/exceptions/" + exceptionId + "/retry", Map.of(), null, actor(user));
    }

    @Operation(summary = "Waive an open posting exception without journalling it; a reason is required")
    @PostMapping("/exceptions/{exceptionId}/waive")
    public JsonNode waive(@PathVariable long exceptionId, @RequestBody Map<String, String> body, Principal user) {
        String reason = body == null ? null : body.get("reason");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to waive an exception");
        }
        return client.post("/exceptions/" + exceptionId + "/waive", Map.of(), Map.of("reason", reason.trim()), actor(user));
    }

    @Operation(summary = "The latest stored reconciliation for a date (default: the business date)")
    @GetMapping("/reconciliation")
    public JsonNode reconciliation(@RequestParam(required = false) String businessDate) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("businessDate", date(businessDate, "businessDate"));
        return client.get("/reconciliation", q);
    }

    @Operation(summary = "Run the reconciliation of the ledger to the loan sub-ledger now (current business date only)")
    @PostMapping("/reconciliation/run")
    public JsonNode runReconciliation(Principal user) {
        return client.post("/reconciliation/run", Map.of(), null, actor(user));
    }

    // ---- checks

    private static String code(String v, String name) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String t = v.trim();
        if (!CODE.matcher(t).matches()) {
            throw new IllegalArgumentException(name + " may only contain letters, digits, '_', '-' and '.'");
        }
        return t;
    }

    private static String date(String v, String name) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be a date in the form yyyy-MM-dd");
        }
    }

    private static String actor(Principal user) {
        return user == null ? "admin-portal" : user.getName();
    }
}
