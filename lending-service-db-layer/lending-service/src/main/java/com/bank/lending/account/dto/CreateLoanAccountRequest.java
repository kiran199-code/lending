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
        LoanAccount.DayCountConvention dayCountConvention,
        @Schema(description = "Optional. Defaults to the product's frequency; must be one the product allows. Tenure must be a multiple of it.")
        LoanAccount.RepaymentFrequency repaymentFrequency,
        @Schema(description = "Optional months of interest-only repayment at the start. Defaults to the product's; cannot exceed its maximum.")
        Integer moratoriumMonths,
        @Schema(description = "BALLOON products only. Optional % of the principal left for the last instalment. Defaults to the product's; cannot exceed its maximum.")
        BigDecimal balloonPercent,
        @Schema(description = "STEP_UP_EMI products only. Optional yearly rise of the instalment in %. Defaults to the product's; cannot exceed its maximum.")
        BigDecimal stepUpPercent
) {
    /** Request that takes every schedule parameter from the product. */
    public CreateLoanAccountRequest(Long customerId, Long sanctionRefId, String productCode,
                                    LoanAccount.AccountType accountType, BigDecimal sanctionedAmount, Integer tenureMonths,
                                    LoanAccount.RateType rateType, BigDecimal interestRate, String benchmarkRateRef,
                                    BigDecimal spread, LoanAccount.DayCountConvention dayCountConvention) {
        this(customerId, sanctionRefId, productCode, accountType, sanctionedAmount, tenureMonths, rateType,
                interestRate, benchmarkRateRef, spread, dayCountConvention, null, null, null, null);
    }
}
