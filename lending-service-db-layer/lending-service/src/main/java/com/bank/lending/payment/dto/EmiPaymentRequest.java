package com.bank.lending.payment.dto;

import com.bank.lending.payment.domain.EmiTransaction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Any positive amount: it is applied to what is due, oldest first; anything left over is held as an advance. */
public record EmiPaymentRequest(
        @NotNull LocalDate valueDate,
        @NotNull @Positive BigDecimal amount,
        @NotNull EmiTransaction.PaymentChannel channel
) {
}
