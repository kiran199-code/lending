package com.bank.lending.product.dto;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.product.domain.LoanProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Create / update body for a loan product. On update, productCode in the body is ignored (the code
 * in the URL identifies the product and cannot be changed). Every min/max left null means "no limit".
 */
public record LoanProductRequest(
        @Schema(description = "Unique product code, up to 10 characters (letters, digits, - and _)", example = "PL02")
        @NotBlank @Size(max = 10) @Pattern(regexp = "[A-Za-z0-9_-]+", message = "letters, digits, - and _ only")
        String productCode,
        @NotBlank @Size(max = 100) String productName,
        @Size(max = 500) String description,
        @NotNull LoanAccount.AccountType accountType,
        @NotNull LoanAccount.RateType rateType,
        @Schema(description = "Defaults to EMI (the only supported schedule type)")
        LoanAccount.ScheduleType scheduleType,
        @Schema(description = "Defaults to ACT_365")
        LoanAccount.DayCountConvention dayCountConvention,
        @Schema(description = "Required for FLOATING products, e.g. REPO, MCLR_1Y", example = "REPO")
        @Size(max = 20) String benchmarkRateRef,
        @Schema(description = "Annual %, applied to an account that does not give its own rate")
        @NotNull BigDecimal defaultInterestRate,
        @Schema(description = "Applied to a floating account that does not give its own spread")
        BigDecimal defaultSpread,
        BigDecimal minInterestRate,
        BigDecimal maxInterestRate,
        BigDecimal minSpread,
        BigDecimal maxSpread,
        BigDecimal minSanctionedAmount,
        BigDecimal maxSanctionedAmount,
        Integer minTenureMonths,
        Integer maxTenureMonths,
        @Schema(description = "Defaults to ACTIVE. An INACTIVE product cannot be used for new accounts.")
        LoanProduct.Status status
) {
}
