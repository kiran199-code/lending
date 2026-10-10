package com.bank.lending.account.web;

import com.bank.lending.account.dto.BulkCreateLoanAccountsRequest;
import com.bank.lending.account.dto.BulkDisburseRequest;
import com.bank.lending.account.dto.BulkOperationResponse;
import com.bank.lending.account.service.LoanAccountBulkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loan-accounts/bulk")
@RequiredArgsConstructor
@Tag(name = "Loan Accounts - Bulk",
        description = "Create or disburse many accounts in one call. Always answers 200 with a result per item.")
public class LoanAccountBulkController {

    private static final String CREATE_EXAMPLE = """
            {
              "accounts": [
                {
                  "customerId": 2001, "sanctionRefId": 910001, "productCode": "PL01",
                  "accountType": "UNSECURED", "sanctionedAmount": 300000.00, "tenureMonths": 24,
                  "rateType": "FIXED", "interestRate": 11.25, "dayCountConvention": "ACT_365"
                },
                {
                  "customerId": 2002, "sanctionRefId": 910002, "productCode": "PL01",
                  "accountType": "UNSECURED", "sanctionedAmount": 500000.00, "tenureMonths": 36,
                  "rateType": "FIXED", "interestRate": 10.5, "dayCountConvention": "ACT_365"
                },
                {
                  "customerId": 2003, "sanctionRefId": 910003, "productCode": "PL01",
                  "accountType": "UNSECURED", "sanctionedAmount": 750000.00, "tenureMonths": 48,
                  "rateType": "FIXED", "interestRate": 9.75, "dayCountConvention": "ACT_365"
                }
              ]
            }
            """;

    private static final String DISBURSE_EXAMPLE = """
            {
              "defaultFirstEmiDueDate": "2026-11-05",
              "items": [
                { "accountId": 1 },
                { "accountId": 2, "disbursedAmount": 250000.00 },
                { "accountId": 3, "firstEmiDueDate": "2026-12-01" }
              ]
            }
            """;

    private final LoanAccountBulkService bulkService;

    @Operation(summary = "Create many loan accounts at once",
            description = "Same rules as single create, applied to each item. Re-sending a sanctionRefId that already "
                    + "exists returns that account (outcome ALREADY_EXISTS) rather than creating a duplicate. "
                    + "A malformed item (for example a zero amount) rejects the whole request with 400 and "
                    + "says which item; business failures are reported per item. Maximum 200 items.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            mediaType = "application/json",
            examples = @ExampleObject(name = "Three fixed-rate personal loans", value = CREATE_EXAMPLE)))
    @PostMapping("/create")
    public BulkOperationResponse createAccounts(@Valid @RequestBody BulkCreateLoanAccountsRequest request) {
        return bulkService.createAccounts(request.accounts());
    }

    @Operation(summary = "Disburse many loan accounts at once",
            description = "Disburses each account (full, or part when disbursedAmount is smaller than the sanctioned "
                    + "amount) and generates its repayment schedule. Leave disbursedAmount out for a full disbursement. "
                    + "Accounts that are already active come back as ALREADY_ACTIVE. Mortgage accounts without a "
                    + "registered charge fail with a message. Use the accountId values from the bulk create response. "
                    + "Maximum 200 items.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
            mediaType = "application/json",
            examples = @ExampleObject(name = "Full, part and own-date disbursement", value = DISBURSE_EXAMPLE)))
    @PostMapping("/disburse")
    public BulkOperationResponse disburseAccounts(@Valid @RequestBody BulkDisburseRequest request) {
        return bulkService.disburseAccounts(request);
    }
}
