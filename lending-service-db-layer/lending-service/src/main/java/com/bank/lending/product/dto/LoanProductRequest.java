package com.bank.lending.product.dto;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.product.domain.LoanProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

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
        @Schema(description = "EMI (default), EQUAL_PRINCIPAL, INTEREST_ONLY, BALLOON or STEP_UP_EMI")
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
        @Schema(description = "Default repayment frequency: MONTHLY (default), QUARTERLY, HALF_YEARLY or YEARLY")
        LoanAccount.RepaymentFrequency repaymentFrequency,
        @Schema(description = "Frequencies an account may choose instead of the default. Defaults to just the default frequency.")
        List<LoanAccount.RepaymentFrequency> allowedRepaymentFrequencies,
        @Schema(description = "Default months of interest-only repayment at the start (0 = none). Not for INTEREST_ONLY.")
        Integer moratoriumMonths,
        @Schema(description = "Most moratorium months an account may choose. Empty = no limit.")
        Integer maxMoratoriumMonths,
        @Schema(description = "BALLOON only: default % of the principal left for the last instalment (above 0, below 100)")
        BigDecimal balloonPercent,
        @Schema(description = "BALLOON only: highest balloon % an account may choose. Empty = no limit.")
        BigDecimal maxBalloonPercent,
        @Schema(description = "STEP_UP_EMI only: default yearly rise of the instalment in % (above 0, up to 100)")
        BigDecimal stepUpPercent,
        @Schema(description = "STEP_UP_EMI only: highest step-up % an account may choose. Empty = no limit.")
        BigDecimal maxStepUpPercent,
        @Schema(description = "Penal interest on overdue instalments. Omit all four penal fields to leave them as they are (new product: on, 24% a year, 0 grace days, no cap).")
        Boolean penalInterestEnabled,
        @Schema(description = "Penal interest rate, % a year, 0 to 99.9999. Default 24.")
        BigDecimal penalInterestRate,
        @Schema(description = "Days after the due date before penal interest starts. Default 0.")
        Integer penalGraceDays,
        @Schema(description = "Most penal interest one demand may collect, as a % of its principal + interest. Empty = no cap.")
        BigDecimal penalCapPercent,
        @Schema(description = "Defaults to ACTIVE. An INACTIVE product cannot be used for new accounts.")
        LoanProduct.Status status,
        @Schema(description = "Update only: the version you loaded. If the product has changed since, the update is refused (409).")
        Integer expectedVersion
) {
    /** Body without the penal interest fields (they are left as they are). */
    public LoanProductRequest(String productCode, String productName, String description,
                              LoanAccount.AccountType accountType, LoanAccount.RateType rateType,
                              LoanAccount.ScheduleType scheduleType, LoanAccount.DayCountConvention dayCountConvention,
                              String benchmarkRateRef, BigDecimal defaultInterestRate, BigDecimal defaultSpread,
                              BigDecimal minInterestRate, BigDecimal maxInterestRate,
                              BigDecimal minSpread, BigDecimal maxSpread,
                              BigDecimal minSanctionedAmount, BigDecimal maxSanctionedAmount,
                              Integer minTenureMonths, Integer maxTenureMonths,
                              LoanAccount.RepaymentFrequency repaymentFrequency,
                              List<LoanAccount.RepaymentFrequency> allowedRepaymentFrequencies,
                              Integer moratoriumMonths, Integer maxMoratoriumMonths,
                              BigDecimal balloonPercent, BigDecimal maxBalloonPercent,
                              BigDecimal stepUpPercent, BigDecimal maxStepUpPercent,
                              LoanProduct.Status status, Integer expectedVersion) {
        this(productCode, productName, description, accountType, rateType, scheduleType, dayCountConvention,
                benchmarkRateRef, defaultInterestRate, defaultSpread, minInterestRate, maxInterestRate,
                minSpread, maxSpread, minSanctionedAmount, maxSanctionedAmount, minTenureMonths, maxTenureMonths,
                repaymentFrequency, allowedRepaymentFrequencies, moratoriumMonths, maxMoratoriumMonths,
                balloonPercent, maxBalloonPercent, stepUpPercent, maxStepUpPercent,
                null, null, null, null, status, expectedVersion);
    }

    /** Body without the schedule parameters (monthly, no moratorium / balloon / step-up). */
    public LoanProductRequest(String productCode, String productName, String description,
                              LoanAccount.AccountType accountType, LoanAccount.RateType rateType,
                              LoanAccount.ScheduleType scheduleType, LoanAccount.DayCountConvention dayCountConvention,
                              String benchmarkRateRef, BigDecimal defaultInterestRate, BigDecimal defaultSpread,
                              BigDecimal minInterestRate, BigDecimal maxInterestRate,
                              BigDecimal minSpread, BigDecimal maxSpread,
                              BigDecimal minSanctionedAmount, BigDecimal maxSanctionedAmount,
                              Integer minTenureMonths, Integer maxTenureMonths,
                              LoanProduct.Status status, Integer expectedVersion) {
        this(productCode, productName, description, accountType, rateType, scheduleType, dayCountConvention,
                benchmarkRateRef, defaultInterestRate, defaultSpread, minInterestRate, maxInterestRate,
                minSpread, maxSpread, minSanctionedAmount, maxSanctionedAmount, minTenureMonths, maxTenureMonths,
                null, null, null, null, null, null, null, null, status, expectedVersion);
    }
}
