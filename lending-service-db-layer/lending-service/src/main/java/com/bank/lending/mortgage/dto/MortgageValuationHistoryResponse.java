package com.bank.lending.mortgage.dto;

import com.bank.lending.mortgage.domain.MortgageValuationHistory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record MortgageValuationHistoryResponse(
        Long valuationId,
        Long mortgageId,
        BigDecimal valuationAmount,
        LocalDate valuationDate,
        String valuerRef,
        MortgageValuationHistory.ValuationType valuationType,
        OffsetDateTime createdAt
) {
    public static MortgageValuationHistoryResponse from(MortgageValuationHistory v) {
        return new MortgageValuationHistoryResponse(
                v.getValuationId(), v.getMortgageId(), v.getValuationAmount(), v.getValuationDate(),
                v.getValuerRef(), v.getValuationType(), v.getCreatedAt()
        );
    }
}
