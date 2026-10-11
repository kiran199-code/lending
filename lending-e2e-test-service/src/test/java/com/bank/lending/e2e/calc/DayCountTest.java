package com.bank.lending.e2e.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bank.lending.e2e.calc.DayCount.Convention;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DayCountTest {

    private static BigDecimal yf(String a, String b, Convention c) {
        return DayCount.yearFraction(LocalDate.parse(a), LocalDate.parse(b), c).setScale(8, RoundingMode.HALF_UP);
    }

    @Test
    void act365() {
        assertEquals(new BigDecimal("0.08219178"), yf("2026-01-01", "2026-01-31", Convention.ACT_365));
    }

    @Test
    void act360() {
        assertEquals(new BigDecimal("0.08333333"), yf("2026-01-01", "2026-01-31", Convention.ACT_360));
    }

    @Test
    void actActSplitsAcrossLeapYear() {
        // 2023-12-31 -> 2024-01-02 : one day in 2023 (365) and one in 2024 (366)
        BigDecimal expected = BigDecimal.ONE.divide(BigDecimal.valueOf(365), 8, RoundingMode.HALF_UP)
                .add(BigDecimal.ONE.divide(BigDecimal.valueOf(366), 8, RoundingMode.HALF_UP));
        assertEquals(expected.doubleValue(), yf("2023-12-31", "2024-01-02", Convention.ACT_ACT).doubleValue(), 1e-7);
    }

    @Test
    void thirty360UsEndOfFebruaryRule() {
        // 28 Feb 2026 is the last day of February -> treated as day 30; 31 Mar -> 30 : exactly 30 days
        assertEquals(new BigDecimal("0.08333333"), yf("2026-02-28", "2026-03-31", Convention.THIRTY_360));
    }

    @Test
    void thirtyE360CapsDaysAt30() {
        // 31 Jan -> 28 Feb : D1=30, D2=28 -> 28 days
        assertEquals(new BigDecimal("0.07777778"), yf("2026-01-31", "2026-02-28", Convention.THIRTY_E_360));
    }

    @Test
    void parsesApiAndDisplayTokens() {
        assertEquals(Convention.THIRTY_E_360, Convention.parse("30E/360"));
        assertEquals(Convention.THIRTY_360, Convention.parse("30/360"));
        assertEquals(Convention.ACT_365, Convention.parse("ACT/365"));
        assertEquals(Convention.ACT_ACT, Convention.parse("ACT_ACT"));
    }

    @Test
    void dailyInterestUsesTheConventionBasis() {
        BigDecimal p = new BigDecimal("100000");
        BigDecimal r = new BigDecimal("10");
        assertEquals(new BigDecimal("27.40"), DayCount.dailyInterest(p, r, Convention.ACT_365, LocalDate.parse("2026-06-01")));
        assertEquals(new BigDecimal("27.32"), DayCount.dailyInterest(p, r, Convention.ACT_ACT, LocalDate.parse("2028-06-01")));
        assertEquals(new BigDecimal("27.78"), DayCount.dailyInterest(p, r, Convention.THIRTY_E_360, LocalDate.parse("2026-06-01")));
    }
}
