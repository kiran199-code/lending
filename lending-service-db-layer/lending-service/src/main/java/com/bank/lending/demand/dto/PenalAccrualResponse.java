package com.bank.lending.demand.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Outcome of charging penal interest on one account: how many daily charges were written and their total. */
public record PenalAccrualResponse(
        Long accountId,
        LocalDate businessDate,
        int accrualsCreated,
        BigDecimal amount,
        BigDecimal penalOutstanding
) {
}
