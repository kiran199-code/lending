package com.bank.lending.admin.web;

import com.bank.lending.admin.common.NotFoundException;
import com.bank.lending.admin.dto.AdminDtos.AccountDetail;
import com.bank.lending.admin.dto.AdminDtos.AccountSummary;
import com.bank.lending.admin.dto.AdminDtos.AccrualResponse;
import com.bank.lending.admin.dto.AdminDtos.PageResponse;
import com.bank.lending.admin.dto.AdminDtos.PaymentResponse;
import com.bank.lending.admin.dto.AdminDtos.ScheduleResponse;
import com.bank.lending.admin.repository.AccountRepository;
import com.bank.lending.admin.service.AccountInquiryService;
import com.bank.lending.admin.service.StatementProxyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/accounts")
@Tag(name = "Accounts", description = "Account search and single-account inquiry")
public class AccountController {

    private final AccountRepository accounts;
    private final AccountInquiryService inquiry;
    private final StatementProxyService statements;

    public AccountController(AccountRepository accounts, AccountInquiryService inquiry, StatementProxyService statements) {
        this.accounts = accounts;
        this.inquiry = inquiry;
        this.statements = statements;
    }

    @Operation(summary = "Search accounts (account number, customer id, sanction ref) with filters and paging")
    @GetMapping
    public PageResponse<AccountSummary> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String product,
            @RequestParam(required = false) Long customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "desc") String dir) {
        return accounts.search(new AccountRepository.Filter(q, status, type, product, customerId), page, size, sort,
                "desc".equalsIgnoreCase(dir));
    }

    @Operation(summary = "Account details with derived figures (outstanding, overdue, days past due, accrued, paid)")
    @GetMapping("/{accountId}")
    public AccountDetail detail(@PathVariable long accountId) {
        return inquiry.detail(accountId);
    }

    @Operation(summary = "Same as detail, looked up by account number")
    @GetMapping("/by-number/{accountNumber}")
    public AccountDetail detailByNumber(@PathVariable String accountNumber) {
        return inquiry.detail(inquiry.resolveId(accountNumber));
    }

    @Operation(summary = "Amortization schedule: combined view by default, or one schedule version")
    @GetMapping("/{accountId}/schedule")
    public ScheduleResponse schedule(@PathVariable long accountId, @RequestParam(required = false) Integer version) {
        return inquiry.schedule(accountId, version);
    }

    @Operation(summary = "Daily interest accruals for an account, optionally within a date range")
    @GetMapping("/{accountId}/accruals")
    public AccrualResponse accruals(@PathVariable long accountId,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return inquiry.accruals(accountId, from, to);
    }

    @Operation(summary = "Payment history of an account with totals")
    @GetMapping("/{accountId}/payments")
    public PaymentResponse payments(@PathVariable long accountId) {
        return inquiry.payments(accountId);
    }

    @Operation(summary = "Customer PDF statement, fetched from lending-service")
    @GetMapping("/{accountId}/statement")
    public ResponseEntity<?> statement(@PathVariable long accountId) {
        inquiry.detail(accountId); // 404 for an unknown account
        if (!statements.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", 503, "error", "Service Unavailable",
                            "message", "Statement download is off: set LENDING_SERVICE_URL to the lending-service address"));
        }
        try {
            ResponseEntity<byte[]> upstream = statements.fetch(accountId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            String disposition = upstream.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
            headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    disposition != null ? disposition : "attachment; filename=\"loan-statement-" + accountId + ".pdf\"");
            headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
            return new ResponseEntity<>(upstream.getBody(), headers, HttpStatus.OK);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new NotFoundException("lending-service has no statement for account " + accountId);
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("status", 502, "error", "Bad Gateway",
                            "message", "lending-service answered " + e.getStatusCode().value()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("status", 502, "error", "Bad Gateway",
                            "message", "Could not reach lending-service (it may be waking up; try again)"));
        }
    }
}
