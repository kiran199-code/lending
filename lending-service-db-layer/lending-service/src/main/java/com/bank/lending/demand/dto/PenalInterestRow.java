package com.bank.lending.demand.dto;

import com.bank.lending.demand.domain.PenalInterest;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One daily penal interest charge, for the account's penal interest history. */
public record PenalInterestRow(
        LocalDate businessDate,
        Long demandId,
        BigDecimal baseAmount,
        BigDecimal annualRate,
        BigDecimal amount,
        boolean suspended
) {
    public static PenalInterestRow from(PenalInterest p) {
        return new PenalInterestRow(p.getBusinessDate(), p.getDemandId(), p.getBaseAmount(), p.getAnnualRate(),
                p.getAmount(), Boolean.TRUE.equals(p.getSuspended()));
    }
}
