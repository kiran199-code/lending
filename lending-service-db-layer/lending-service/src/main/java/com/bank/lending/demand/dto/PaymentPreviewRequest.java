package com.bank.lending.demand.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/** paymentType is EMI (the default) or PART_PAYMENT; it decides what happens to money left after everything due is paid. */
public record PaymentPreviewRequest(
        @NotNull LocalDate valueDate,
        @NotNull @Positive BigDecimal amount,
        String paymentType
) {
}
