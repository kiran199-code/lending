package com.bank.lending.mortgage.dto;

import com.bank.lending.mortgage.domain.Mortgage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateMortgageRequest(
        @NotNull Long accountId,
        @NotNull Mortgage.PropertyType propertyType,
        @NotBlank String propertyAddress,
        @NotNull @Positive BigDecimal valuationAmount,
        @NotNull LocalDate valuationDate,
        String valuerRef
) {
}
