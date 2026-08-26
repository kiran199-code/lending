package com.bank.lending.payment.dto;

import com.bank.lending.payment.domain.EmiTransaction;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record ForeclosureRequest(
        @NotNull LocalDate foreclosureDate,
        @NotNull EmiTransaction.PaymentChannel channel
) {
}
