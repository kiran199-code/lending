package com.bank.lending.product.dto;

import com.bank.lending.account.domain.LoanAccount;
import com.bank.lending.product.domain.LoanProduct;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record LoanProductResponse(
        Long productId,
        String productCode,
        String productName,
        String description,
        LoanAccount.AccountType accountType,
        LoanAccount.RateType rateType,
        LoanAccount.ScheduleType scheduleType,
        LoanAccount.DayCountConvention dayCountConvention,
        String benchmarkRateRef,
        BigDecimal defaultInterestRate,
        BigDecimal defaultSpread,
        BigDecimal minInterestRate,
        BigDecimal maxInterestRate,
        BigDecimal minSpread,
        BigDecimal maxSpread,
        BigDecimal minSanctionedAmount,
        BigDecimal maxSanctionedAmount,
        Integer minTenureMonths,
        Integer maxTenureMonths,
        LoanAccount.RepaymentFrequency repaymentFrequency,
        List<LoanAccount.RepaymentFrequency> allowedRepaymentFrequencies,
        Integer moratoriumMonths,
        Integer maxMoratoriumMonths,
        BigDecimal balloonPercent,
        BigDecimal maxBalloonPercent,
        BigDecimal stepUpPercent,
        BigDecimal maxStepUpPercent,
        Boolean penalInterestEnabled,
        BigDecimal penalInterestRate,
        Integer penalGraceDays,
        BigDecimal penalCapPercent,
        LoanProduct.Status status,
        Integer version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static LoanProductResponse from(LoanProduct p) {
        return new LoanProductResponse(
                p.getProductId(), p.getProductCode(), p.getProductName(), p.getDescription(),
                p.getAccountType(), p.getRateType(), p.getScheduleType(), p.getDayCountConvention(),
                p.getBenchmarkRateRef(), p.getDefaultInterestRate(), p.getDefaultSpread(),
                p.getMinInterestRate(), p.getMaxInterestRate(), p.getMinSpread(), p.getMaxSpread(),
                p.getMinSanctionedAmount(), p.getMaxSanctionedAmount(),
                p.getMinTenureMonths(), p.getMaxTenureMonths(),
                p.getRepaymentFrequency(), p.allowedFrequencies(),
                p.getMoratoriumMonths(), p.getMaxMoratoriumMonths(),
                p.getBalloonPercent(), p.getMaxBalloonPercent(),
                p.getStepUpPercent(), p.getMaxStepUpPercent(),
                p.getPenalInterestEnabled(), p.getPenalInterestRate(), p.getPenalGraceDays(), p.getPenalCapPercent(),
                p.getStatus(), p.getVersion(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
