package com.bank.lending.chit.domain;

import com.bank.lending.chit.domain.rules.ChitRules;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A valid baseline group used by the tests: the worked example (P = 1,00,000, N = 20, S = 5,000, 5% commission). */
public final class TestRules {

    private TestRules() {
    }

    public static ChitRules.Builder baseline() {
        return ChitRules.builder()
                .chitValue(new BigDecimal("100000.00"))
                .ticketCount(20)
                .cycleCount(20)
                .subscription(new BigDecimal("5000.00"))
                .instalmentDueDay(5)
                .auctionDay(10)
                .startDate(LocalDate.of(2026, 11, 1))
                .commissionPct(new BigDecimal("5"))
                .maxCommissionPct(new BigDecimal("5")) // test value; the real cap is TODO-VERIFY
                .minDiscountPct(new BigDecimal("5"))
                .maxDiscountPct(new BigDecimal("40"));
    }

    public static ChitRules valid() {
        return baseline().build();
    }
}
