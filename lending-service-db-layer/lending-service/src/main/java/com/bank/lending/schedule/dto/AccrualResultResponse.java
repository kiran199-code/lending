package com.bank.lending.schedule.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Outcome of a single-account accrual. Re-running for the same date reports ALREADY_POSTED and changes nothing. */
public record AccrualResultResponse(
        Long accountId,
        LocalDate businessDate,
        Outcome outcome,
        BigDecimal accruedAmount,
        BigDecimal outstandingPrincipal,
        String reason
) {
    public enum Outcome { POSTED, ALREADY_POSTED, SKIPPED }
}
