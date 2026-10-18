package com.bank.lending.account.dto;

import com.bank.lending.account.domain.LoanAccount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record LoanAccountResponse(
        Long accountId,
        String accountNumber,
        Long customerId,
        Long sanctionRefId,
        String productCode,
        Long productId,
        LoanAccount.AccountType accountType,
        BigDecimal sanctionedAmount,
        BigDecimal disbursedAmount,
        Integer tenureMonths,
        LoanAccount.ScheduleType scheduleType,
        LoanAccount.RateType rateType,
        BigDecimal interestRate,
        String benchmarkRateRef,
        BigDecimal spread,
        LoanAccount.DayCountConvention dayCountConvention,
        LoanAccount.RepaymentFrequency repaymentFrequency,
        Integer moratoriumMonths,
        BigDecimal balloonPercent,
        BigDecimal stepUpPercent,
        BigDecimal emiAmount,
        LocalDate emiStartDate,
        String currencyCode,
        String branchCode,
        LoanAccount.AccountStatus accountStatus,
        Long mortgageId,
        String limitFacilityRef,
        String limitReservationRef,
        Integer version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static LoanAccountResponse from(LoanAccount a) {
        return new LoanAccountResponse(
                a.getAccountId(), a.getAccountNumber(), a.getCustomerId(), a.getSanctionRefId(),
                a.getProductCode(), a.getProductId(), a.getAccountType(), a.getSanctionedAmount(), a.getDisbursedAmount(),
                a.getTenureMonths(), a.getScheduleType(), a.getRateType(), a.getInterestRate(), a.getBenchmarkRateRef(),
                a.getSpread(), a.getDayCountConvention(),
                a.getRepaymentFrequency(), a.getMoratoriumMonths(), a.getBalloonPercent(), a.getStepUpPercent(),
                a.getEmiAmount(), a.getEmiStartDate(), a.getCurrencyCode(), a.getBranchCode(),
                a.getAccountStatus(), a.getMortgageId(), a.getLimitFacilityRef(), a.getLimitReservationRef(), a.getVersion(), a.getCreatedAt(), a.getUpdatedAt()
        );
    }
}
