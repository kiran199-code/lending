package com.bank.lending.mortgage.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterChargeRequest(
        @NotBlank String cersaiRegistrationRef,
        @NotBlank String mortgageDeedRef
) {
}
