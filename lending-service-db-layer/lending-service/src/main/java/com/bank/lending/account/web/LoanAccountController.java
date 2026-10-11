package com.bank.lending.account.web;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.account.dto.AccountRateResetRequest;
import com.bank.lending.account.dto.CreateLoanAccountRequest;
import com.bank.lending.account.dto.DelinquencyResultResponse;
import com.bank.lending.account.dto.RateResetResultResponse;
import com.bank.lending.account.dto.DelinquencyClassificationRequest;
import com.bank.lending.account.dto.DisburseRequest;
import com.bank.lending.account.dto.FloatingRateResetRequest;
import com.bank.lending.account.dto.LoanAccountResponse;
import com.bank.lending.account.service.LoanAccountService;
import com.bank.lending.common.dto.BatchOperationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/loan-accounts")
@RequiredArgsConstructor
@Tag(name = "Loan Accounts", description = "Account creation, disbursement, floating-rate resets, delinquency classification")
public class LoanAccountController {

    private final LoanAccountService loanAccountService;

    @Operation(summary = "Create a loan account from a product (idempotent on sanctionRefId)",
            description = "Fields left out are defaulted from the loan product; supplied values must stay inside the product limits.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            mediaType = "application/json",
            examples = {
                    @ExampleObject(name = "Defaults from product", value = """
                            {
                              "customerId": 1001, "sanctionRefId": 900001, "productCode": "PL01",
                              "sanctionedAmount": 500000.00, "tenureMonths": 36
                            }
                            """),
                    @ExampleObject(name = "Override rate (inside the product band)", value = """
                            {
                              "customerId": 1002, "sanctionRefId": 900002, "productCode": "PL01",
                              "sanctionedAmount": 800000.00, "tenureMonths": 60,
                              "interestRate": 11.25, "dayCountConvention": "ACT_365"
                            }
                            """),
                    @ExampleObject(name = "Quarterly repayment with a 12-month moratorium", value = """
                            {
                              "customerId": 1004, "sanctionRefId": 900004, "productCode": "EM01",
                              "sanctionedAmount": 1200000.00, "tenureMonths": 84,
                              "repaymentFrequency": "QUARTERLY", "moratoriumMonths": 12
                            }
                            """),
                    @ExampleObject(name = "Balloon auto loan (40% balloon)", value = """
                            {
                              "customerId": 1005, "sanctionRefId": 900005, "productCode": "AB01",
                              "sanctionedAmount": 900000.00, "tenureMonths": 48, "balloonPercent": 40
                            }
                            """),
                    @ExampleObject(name = "Floating home loan, own spread", value = """
                            {
                              "customerId": 1003, "sanctionRefId": 900003, "productCode": "HL01",
                              "sanctionedAmount": 6000000.00, "tenureMonths": 240,
                              "spread": 2.25, "interestRate": 8.75
                            }
                            """)
            }))
    @PostMapping
    public ResponseEntity<LoanAccountResponse> createAccount(@Valid @RequestBody CreateLoanAccountRequest request,
                                                               UriComponentsBuilder uriBuilder) {
        LoanAccount account = loanAccountService.createAccount(request);
        URI location = uriBuilder.path("/api/v1/loan-accounts/{id}").buildAndExpand(account.getAccountId()).toUri();
        return ResponseEntity.created(location).body(LoanAccountResponse.from(account));
    }

    @Operation(summary = "Get a loan account by its internal ID")
    @GetMapping("/{accountId}")
    public LoanAccountResponse getById(@PathVariable Long accountId) {
        return LoanAccountResponse.from(loanAccountService.getById(accountId));
    }

    @Operation(summary = "Get a loan account by its account number")
    @GetMapping("/by-account-number/{accountNumber}")
    public LoanAccountResponse getByAccountNumber(@PathVariable String accountNumber) {
        return LoanAccountResponse.from(loanAccountService.getByAccountNumber(accountNumber));
    }

    @Operation(summary = "List loan accounts for a customer")
    @GetMapping
    public Page<LoanAccountResponse> listByCustomer(@RequestParam Long customerId,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return loanAccountService.findByCustomerId(customerId, PageRequest.of(page, size))
                .map(LoanAccountResponse::from);
    }

    @Operation(summary = "Disburse a sanctioned account (full or part) and generate its initial schedule")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            mediaType = "application/json",
            examples = @ExampleObject(name = "Full disbursement", value = """
                    { "disbursedAmount": 500000.00, "firstEmiDueDate": "2026-11-05" }
                    """)))
    @PostMapping("/{accountId}/disburse")
    public LoanAccountResponse disburse(@PathVariable Long accountId, @Valid @RequestBody DisburseRequest request) {
        return LoanAccountResponse.from(
                loanAccountService.disburse(accountId, request.disbursedAmount(), request.firstEmiDueDate()));
    }

    @Operation(summary = "Reset one floating-rate account from the benchmark rate in force on a business date (idempotent; used by the rate-reset batch jobs)")
    @PostMapping("/{accountId}/rate-reset")
    public RateResetResultResponse resetRate(@PathVariable Long accountId,
                                             @Valid @RequestBody AccountRateResetRequest request) {
        return loanAccountService.resetRateForAccount(accountId, request.businessDate(), request.benchmarkRateRef());
    }

    @Operation(summary = "Classify one account's delinquency (days past due, SMA/NPA status) as of a date (idempotent; used by the DPD/NPA batch jobs)")
    @PostMapping("/{accountId}/delinquency-classification")
    public DelinquencyResultResponse classifyAccount(@PathVariable Long accountId,
                                                     @Valid @RequestBody DelinquencyClassificationRequest request) {
        return loanAccountService.classifyOne(accountId, request.asOfDate());
    }

    @Operation(summary = "Batch: apply a benchmark rate change to all active floating-rate accounts referencing it")
    @PostMapping("/rate-resets")
    public BatchOperationResponse applyFloatingRateReset(@Valid @RequestBody FloatingRateResetRequest request) {
        loanAccountService.applyFloatingRateReset(request.benchmarkRateRef(), request.newBenchmarkRate());
        return BatchOperationResponse.completed("Floating rate reset applied for benchmark " + request.benchmarkRateRef());
    }

    @Operation(summary = "Batch: re-derive delinquency/NPA status for all tracked accounts as of a date")
    @PostMapping("/delinquency-classifications")
    public BatchOperationResponse classifyDelinquency(@Valid @RequestBody DelinquencyClassificationRequest request) {
        loanAccountService.classifyDelinquency(request.asOfDate());
        return BatchOperationResponse.completed("Delinquency classification run for " + request.asOfDate());
    }
}
