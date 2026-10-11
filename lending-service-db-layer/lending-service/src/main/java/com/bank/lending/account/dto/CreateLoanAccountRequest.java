package com.bank.lending.account.dto;

import com.bank.lending.account.domain.LoanAccount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * productCode selects a loan product (GET /api/v1/loan-products). Every optional field below is
 * defaulted from that product when omitted; when supplied it overrides the default but must stay
 * inside the product's limits (and accountType / rateType must equal the product's).
 */
public record CreateLoanAccountRequest(
        @NotNull Long customerId,
        @NotNull Long sanctionRefId,
        @Schema(description = "Code of an ACTIVE loan product", example = "PL01")
        @NotBlank String productCode,
        @Schema(description = "Optional. Defaults to the product's account type; must equal it if given.")
        LoanAccount.AccountType accountType,
        @NotNull @Positive BigDecimal sanctionedAmount,
        @NotNull @Positive Integer tenureMonths,
        @Schema(description = "Optional. Defaults to the product's rate type; must equal it if given.")
        LoanAccount.RateType rateType,
        @Schema(description = "Optional annual %. Defaults to the product's default rate; must be inside the product's rate band.")
        BigDecimal interestRate,
        @Schema(description = "FLOATING only. Defaults to the product's benchmark.")
        String benchmarkRateRef,
        @Schema(description = "FLOATING only. Defaults to the product's default spread; must be inside the product's spread band.")
        BigDecimal spread,
        @Schema(description = "Optional. Defaults to the product's day-count convention.")
        LoanAccount.DayCountConvention dayCountConvention
) {
}
