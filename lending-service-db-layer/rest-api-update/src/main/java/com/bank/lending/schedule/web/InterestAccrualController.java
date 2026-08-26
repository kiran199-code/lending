package com.bank.lending.schedule.web;

import com.bank.lending.common.dto.BatchOperationResponse;
import com.bank.lending.common.exception.ResourceNotFoundException;
import com.bank.lending.schedule.dto.InterestAccrualResponse;
import com.bank.lending.schedule.service.InterestAccrualService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@Tag(name = "Interest Accrual", description = "Daily interest accrual batch and per-account accrual lookups")
public class InterestAccrualController {

    private final InterestAccrualService interestAccrualService;

    @Operation(summary = "Batch: run daily interest accrual for all active accounts as of a business date")
    @PostMapping("/api/v1/interest-accruals/run")
    public BatchOperationResponse runDailyAccrual(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        interestAccrualService.runDailyAccrual(businessDate);
        return BatchOperationResponse.completed("Daily accrual run for " + businessDate);
    }

    @Operation(summary = "Get the accrual posted for an account on a given business date, if any")
    @GetMapping("/api/v1/loan-accounts/{accountId}/interest-accruals/{businessDate}")
    public InterestAccrualResponse getAccrual(
            @PathVariable Long accountId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return interestAccrualService.getAccrual(accountId, businessDate)
                .map(InterestAccrualResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No accrual found for account " + accountId + " on " + businessDate));
    }
}
