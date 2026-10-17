package com.bank.lending.demand.web;

import com.bank.lending.demand.dto.AmountPayableResponse;
import com.bank.lending.demand.dto.DemandRaiseResponse;
import com.bank.lending.demand.dto.PaymentPreviewRequest;
import com.bank.lending.demand.dto.PaymentPreviewResponse;
import com.bank.lending.demand.service.AmountPayableService;
import com.bank.lending.demand.service.DemandBatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@Tag(name = "Demands", description = "Amounts due on instalments: raising them, what is payable, and how a payment would be applied")
public class DemandController {

    private final DemandBatchService demandBatchService;
    private final AmountPayableService amountPayableService;

    @Operation(summary = "Batch: raise the demand for every instalment due on or before a business date, for all accounts (idempotent)")
    @PostMapping("/api/v1/demands/raise")
    public DemandRaiseResponse raiseAll(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return demandBatchService.raiseAll(businessDate);
    }

    @Operation(summary = "Raise the demands due on or before a business date for one account (idempotent; the EOD batch calls this per account)")
    @PostMapping("/api/v1/loan-accounts/{accountId}/demands")
    public DemandRaiseResponse raiseOne(
            @PathVariable Long accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return demandBatchService.raiseOne(accountId, businessDate);
    }

    @Operation(summary = "How much is payable on a business date: open demands, overdue and due-today amounts, advance, next instalment")
    @GetMapping("/api/v1/loan-accounts/{accountId}/amount-payable")
    public AmountPayableResponse amountPayable(
            @PathVariable Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return amountPayableService.amountPayable(accountId, asOf);
    }

    @Operation(summary = "Show how a payment amount would be applied, without posting it")
    @PostMapping("/api/v1/loan-accounts/{accountId}/payments/preview")
    public PaymentPreviewResponse preview(
            @PathVariable Long accountId,
            @Valid @RequestBody PaymentPreviewRequest request) {
        return amountPayableService.preview(accountId, request);
    }
}
