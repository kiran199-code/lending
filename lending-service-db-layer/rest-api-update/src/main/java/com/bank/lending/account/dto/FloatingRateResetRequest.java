package com.bank.lending.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record FloatingRateResetRequest(
        @NotBlank String benchmarkRateRef,
        @NotNull BigDecimal newBenchmarkRate
) {
}
