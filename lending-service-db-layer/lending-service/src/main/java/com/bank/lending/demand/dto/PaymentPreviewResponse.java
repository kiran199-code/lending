package com.bank.lending.demand.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** How a payment would be applied if posted now. Nothing is changed. */
public record PaymentPreviewResponse(
        Long accountId,
        LocalDate valueDate,
        BigDecimal amount,
        String paymentType,
        List<AllocationLine> lines,
        BigDecimal toPenalInterest,
        BigDecimal toInterest,
        BigDecimal toPrincipal,
        BigDecimal surplus,
        String surplusTreatment,
        boolean allowed,
        String message
) {
}
