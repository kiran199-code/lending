package com.bank.lending.benchmark.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PublishBenchmarkRateRequest(
        @NotNull @DecimalMin("0.0000") @DecimalMax("99.9999") BigDecimal rate,
        @Size(max = 50) String source
) {
}
