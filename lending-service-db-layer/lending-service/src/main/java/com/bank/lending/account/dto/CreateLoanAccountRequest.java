package com.bank.lending.account.dto;

import com.bank.lending.account.domain.LoanAccount;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateLoanAccountRequest(
        @NotNull Long customerId,
        @NotNull Long sanctionRefId,
        @NotBlank String productCode,
        @NotNull LoanAccount.AccountType accountType,
        @NotNull @Positive BigDecimal sanctionedAmount,
        @NotNull @Positive Integer tenureMonths,
        @NotNull LoanAccount.RateType rateType,
        @NotNull BigDecimal interestRate,
        String benchmarkRateRef,
        BigDecimal spread,
        LoanAccount.DayCountConvention dayCountConvention
) {
}
