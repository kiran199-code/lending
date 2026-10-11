package com.bank.lending.benchmark.dto;

import com.bank.lending.benchmark.domain.BenchmarkRate;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BenchmarkRateResponse(String benchmarkRef, LocalDate effectiveDate, BigDecimal rate, String source) {
    public static BenchmarkRateResponse from(BenchmarkRate r) {
        return new BenchmarkRateResponse(r.getBenchmarkRef(), r.getEffectiveDate(), r.getRate(), r.getSource());
    }
}
