package com.bank.lending.schedule.web;

import com.bank.lending.schedule.dto.AmortizationScheduleEntryResponse;
import com.bank.lending.schedule.service.AmortizationScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/loan-accounts/{accountId}/schedule")
@RequiredArgsConstructor
@Tag(name = "Amortization Schedule", description = "Current EMI schedule for a loan account")
public class AmortizationScheduleController {

    private final AmortizationScheduleService scheduleService;

    @Operation(summary = "Get the current (latest-version) amortization schedule for an account")
    @GetMapping
    public List<AmortizationScheduleEntryResponse> getCurrentSchedule(@PathVariable Long accountId) {
        return scheduleService.getCurrentSchedule(accountId).stream()
                .map(AmortizationScheduleEntryResponse::from)
                .toList();
    }
}
