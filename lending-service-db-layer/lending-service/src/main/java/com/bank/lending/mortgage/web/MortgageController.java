package com.bank.lending.mortgage.web;

import com.bank.lending.mortgage.domain.Mortgage;
import com.bank.lending.mortgage.domain.MortgageValuationHistory;
import com.bank.lending.mortgage.dto.CreateMortgageRequest;
import com.bank.lending.mortgage.dto.MortgageResponse;
import com.bank.lending.mortgage.dto.MortgageValuationHistoryResponse;
import com.bank.lending.mortgage.dto.RecordValuationRequest;
import com.bank.lending.mortgage.dto.RegisterChargeRequest;
import com.bank.lending.mortgage.service.MortgageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/mortgages")
@RequiredArgsConstructor
@Tag(name = "Mortgages", description = "Mortgage collateral creation, charge registration, valuation")
public class MortgageController {

    private final MortgageService mortgageService;

    @Operation(summary = "Create a mortgage for a MORTGAGE-type loan account (idempotent on accountId)")
    @PostMapping
    public ResponseEntity<MortgageResponse> create(@Valid @RequestBody CreateMortgageRequest request,
                                                     UriComponentsBuilder uriBuilder) {
        Mortgage mortgage = mortgageService.createMortgage(request);
        URI location = uriBuilder.path("/api/v1/mortgages/{id}").buildAndExpand(mortgage.getMortgageId()).toUri();
        return ResponseEntity.created(location).body(MortgageResponse.from(mortgage));
    }

    @Operation(summary = "Get a mortgage by ID")
    @GetMapping("/{mortgageId}")
    public MortgageResponse getById(@PathVariable Long mortgageId) {
        return MortgageResponse.from(mortgageService.getById(mortgageId));
    }

    @Operation(summary = "Get the mortgage linked to a loan account")
    @GetMapping("/by-account/{accountId}")
    public MortgageResponse getByAccountId(@PathVariable Long accountId) {
        return MortgageResponse.from(mortgageService.getByAccountId(accountId));
    }

    @Operation(summary = "Register the legal charge, unblocking disbursement for the linked loan account")
    @PostMapping("/{mortgageId}/charge")
    public MortgageResponse registerCharge(@PathVariable Long mortgageId, @Valid @RequestBody RegisterChargeRequest request) {
        return MortgageResponse.from(
                mortgageService.registerCharge(mortgageId, request.cersaiRegistrationRef(), request.mortgageDeedRef()));
    }

    @Operation(summary = "Record a new valuation for a mortgage (initial, periodic review, or trigger-based)")
    @PostMapping("/{mortgageId}/valuations")
    public ResponseEntity<MortgageValuationHistoryResponse> recordValuation(@PathVariable Long mortgageId,
                                                                              @Valid @RequestBody RecordValuationRequest request) {
        MortgageValuationHistory history = mortgageService.recordValuation(mortgageId, request.valuationAmount(),
                request.valuationDate(), request.valuerRef(), request.valuationType());
        return ResponseEntity.status(201).body(MortgageValuationHistoryResponse.from(history));
    }

    @Operation(summary = "List valuation history for a mortgage, most recent first")
    @GetMapping("/{mortgageId}/valuations")
    public List<MortgageValuationHistoryResponse> valuationHistory(@PathVariable Long mortgageId) {
        return mortgageService.getValuationHistory(mortgageId).stream()
                .map(MortgageValuationHistoryResponse::from)
                .toList();
    }

    @Operation(summary = "List mortgages due for periodic revaluation (last valuation before the cutoff date)")
    @GetMapping("/due-for-revaluation")
    public List<MortgageResponse> dueForRevaluation(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cutoffDate) {
        return mortgageService.findDueForRevaluation(cutoffDate).stream()
                .map(MortgageResponse::from)
                .toList();
    }
}
