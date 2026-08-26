package com.bank.lending.schedule.dto;

import com.bank.lending.schedule.domain.InterestAccrual;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record InterestAccrualResponse(
        Long accrualId,
        Long accountId,
        LocalDate businessDate,
        BigDecimal accruedAmount,
        BigDecimal outstandingPrincipal,
        OffsetDateTime createdAt
) {
    public static InterestAccrualResponse from(InterestAccrual a) {
        return new InterestAccrualResponse(
                a.getAccrualId(), a.getAccountId(), a.getBusinessDate(), a.getAccruedAmount(),
                a.getOutstandingPrincipal(), a.getCreatedAt()
        );
    }
}
