package com.bank.lending.account.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record DelinquencyClassificationRequest(
        @NotNull LocalDate asOfDate
) {
}
