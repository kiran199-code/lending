package com.bank.lending.payment.dto;

import com.bank.lending.payment.domain.EmiTransaction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Amount must match the exact EMI due for the next pending installment; otherwise use a part-payment. */
public record EmiPaymentRequest(
        @NotNull LocalDate valueDate,
        @NotNull @Positive BigDecimal amount,
        @NotNull EmiTransaction.PaymentChannel channel
) {
}
