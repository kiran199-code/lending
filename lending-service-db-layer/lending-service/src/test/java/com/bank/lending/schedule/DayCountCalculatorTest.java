package com.bank.lending.schedule;

import com.bank.lending.account.domain.LoanAccount.DayCountConvention;
import com.bank.lending.schedule.util.DayCountCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Spring-free: year fractions and daily accrual under each convention. */
class DayCountCalculatorTest {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.0000001");

    private static BigDecimal fraction(String from, String to, DayCountConvention convention) {
        return DayCountCalculator.yearFraction(LocalDate.parse(from), LocalDate.parse(to), convention);
    }

    @Test
    void actual365And360CountRealDays() {
        assertThat(fraction("2026-01-31", "2026-02-28", DayCountConvention.ACT_365))
                .isCloseTo(new BigDecimal("28").divide(new BigDecimal("365"), 12, java.math.RoundingMode.HALF_UP), within(TOLERANCE));
        assertThat(fraction("2026-01-31", "2026-02-28", DayCountConvention.ACT_360))
                .isCloseTo(new BigDecimal("28").divide(new BigDecimal("360"), 12, java.math.RoundingMode.HALF_UP), within(TOLERANCE));
        assertThat(fraction("2025-12-15", "2026-12-15", DayCountConvention.ACT_365)).isCloseTo(BigDecimal.ONE, within(TOLERANCE));
        assertThat(fraction("2025-12-15", "2026-12-15", DayCountConvention.ACT_360))
                .isCloseTo(new BigDecimal("1.0138889"), within(TOLERANCE));
    }

    @Test
    void actualActualUsesEachYearsOwnLength() {
        // 2027-01-01 .. 2028-01-01 : 365 days of 2027 -> exactly one year
        assertThat(fraction("2027-01-01", "2028-01-01", DayCountConvention.ACT_ACT)).isCloseTo(BigDecimal.ONE, within(TOLERANCE));
        // 2028 is a leap year: 366 days -> still one year
        assertThat(fraction("2028-01-01", "2029-01-01", DayCountConvention.ACT_ACT)).isCloseTo(BigDecimal.ONE, within(TOLERANCE));
        // across the year end: 16 days of 2027 (/365) + 15 days of 2028 (/366)
        BigDecimal expected = new BigDecimal(16).divide(new BigDecimal(365), 12, java.math.RoundingMode.HALF_UP)
                .add(new BigDecimal(15).divide(new BigDecimal(366), 12, java.math.RoundingMode.HALF_UP));
        assertThat(fraction("2027-12-16", "2028-01-15", DayCountConvention.ACT_ACT)).isCloseTo(expected, within(TOLERANCE));
    }

    @Test
    void thirty360UsAndEuropeanDifferAtMonthEnds() {
        // 28 Feb (last day of February) -> 31 Mar: US treats both as day 30, so 30 days;
        // European only turns the 31st into the 30th and keeps day 28, so 32 days
        assertThat(fraction("2027-02-28", "2027-03-31", DayCountConvention.THIRTY_360))
                .isCloseTo(new BigDecimal("30").divide(new BigDecimal("360"), 12, java.math.RoundingMode.HALF_UP), within(TOLERANCE));
        assertThat(fraction("2027-02-28", "2027-03-31", DayCountConvention.THIRTY_E_360))
                .isCloseTo(new BigDecimal("32").divide(new BigDecimal("360"), 12, java.math.RoundingMode.HALF_UP), within(TOLERANCE));
        // a whole calendar year is 360 days in both
        assertThat(fraction("2026-03-15", "2027-03-15", DayCountConvention.THIRTY_360)).isCloseTo(BigDecimal.ONE, within(TOLERANCE));
        assertThat(fraction("2026-03-15", "2027-03-15", DayCountConvention.THIRTY_E_360)).isCloseTo(BigDecimal.ONE, within(TOLERANCE));
    }

    @Test
    void endBeforeStartIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fraction("2026-02-01", "2026-01-01", DayCountConvention.ACT_365))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dailyAccrualUsesTheConventionsYearBasis() {
        LocalDate day = LocalDate.of(2026, 10, 10);
        BigDecimal principal = new BigDecimal("1000000");
        BigDecimal rate = new BigDecimal("12");

        assertThat(DayCountCalculator.dailyInterest(principal, rate, DayCountConvention.ACT_365, day)).isEqualByComparingTo("328.7671");
        assertThat(DayCountCalculator.dailyInterest(principal, rate, DayCountConvention.ACT_360, day)).isEqualByComparingTo("333.3333");
        assertThat(DayCountCalculator.dailyInterest(principal, rate, DayCountConvention.THIRTY_360, day)).isEqualByComparingTo("333.3333");
        assertThat(DayCountCalculator.dailyInterest(principal, rate, DayCountConvention.THIRTY_E_360, day)).isEqualByComparingTo("333.3333");
        assertThat(DayCountCalculator.dailyInterest(principal, rate, DayCountConvention.ACT_ACT, LocalDate.of(2028, 3, 1)))
                .isEqualByComparingTo("327.8689");
    }
}
