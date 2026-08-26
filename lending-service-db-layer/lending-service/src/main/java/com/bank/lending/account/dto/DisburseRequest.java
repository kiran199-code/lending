package com.bank.lending.account.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Disbursed amount may be less than the sanctioned amount for a part-disbursement. */
public record DisburseRequest(
        @NotNull @Positive BigDecimal disbursedAmount,
        @NotNull LocalDate firstEmiDueDate
) {
}
