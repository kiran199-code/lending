package com.bank.lending.demand.web;

import com.bank.lending.demand.dto.PenalAccrualResponse;
import com.bank.lending.demand.dto.PenalInterestRow;
import com.bank.lending.demand.service.PenalInterestService;
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
import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Penal interest", description = "Daily penal interest on overdue instalments, at the rate, grace days and cap of the loan product")
public class PenalInterestController {

    private final PenalInterestService penalService;

    @Operation(summary = "Charge penal interest on one account for every day up to and including a business date (idempotent; the EOD batch calls this per account)")
    @PostMapping("/api/v1/loan-accounts/{accountId}/penal-interest")
    public PenalAccrualResponse accrue(
            @PathVariable Long accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return penalService.accrueForAccount(accountId, businessDate);
    }

    @Operation(summary = "The daily penal interest charges on an account, oldest first")
    @GetMapping("/api/v1/loan-accounts/{accountId}/penal-interest")
    public List<PenalInterestRow> history(@PathVariable Long accountId) {
        return penalService.history(accountId);
    }
}
