package com.bank.lending.mortgage.dto;

import com.bank.lending.mortgage.domain.MortgageValuationHistory;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecordValuationRequest(
        @NotNull @Positive BigDecimal valuationAmount,
        @NotNull LocalDate valuationDate,
        String valuerRef,
        @NotNull MortgageValuationHistory.ValuationType valuationType
) {
}
