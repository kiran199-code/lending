package com.bank.lending.account.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Reset one account's rate from the benchmark rate in force on {@code businessDate}.
 * {@code benchmarkRateRef} is optional: when given, the account is only reset if it references
 * that benchmark (lets a job for one benchmark run safely over a mixed set of accounts).
 */
public record AccountRateResetRequest(
        @NotNull LocalDate businessDate,
        String benchmarkRateRef
) {
}
