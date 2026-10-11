package com.bank.lending.account.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RateResetResultResponse(
        Long accountId,
        LocalDate businessDate,
        Outcome outcome,
        String benchmarkRateRef,
        BigDecimal benchmarkRate,
        BigDecimal previousRate,
        BigDecimal newRate,
        String reason
) {
    /** RESET: rate changed and the remaining schedule was regenerated. UNCHANGED: computed rate equals the current rate. SKIPPED: account not eligible. */
    public enum Outcome { RESET, UNCHANGED, SKIPPED }
}
