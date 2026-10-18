package com.bank.lending.lcm.collateral;

import com.bank.lending.lcm.collateral.ValuationCalculator.Contribution;
import com.bank.lending.lcm.collateral.ValuationCalculator.Input;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ValuationCalculatorTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

    static BigDecimal d(String v) { return new BigDecimal(v); }

    static Input input(String status, String pct, String base, LocalDate valued, String haircut, String ltv, String prior, int validity,
                       boolean needsInsurance, LocalDate insuranceExpiry) {
        return new Input("COL-1", status, d(pct), base == null ? null : d(base), valued, d(haircut), d(ltv), d(prior), validity, needsInsurance, insuranceExpiry);
    }

    @Test
    void eligibleValueAppliesHaircutThenSubtractsPriorCharge() {
        // 10,000,000 less 20% haircut = 8,000,000; less prior charge 500,000 = 7,500,000
        assertThat(ValuationCalculator.eligibleValue(d("10000000.00"), d("20.00"), d("500000.00"))).isEqualByComparingTo("7500000.00");
    }

    @Test
    void eligibleValueNeverGoesNegative() {
        assertThat(ValuationCalculator.eligibleValue(d("1000.00"), d("20.00"), d("900.00"))).isEqualByComparingTo("0.00");
    }

    @Test
    void lendableValueIsEligibleTimesMaxLtv() {
        assertThat(ValuationCalculator.lendableValue(d("7500000.00"), d("75.00"))).isEqualByComparingTo("5625000.00");
    }

    @Test
    void contributionScalesByAllocationPercentage() {
        Contribution c = ValuationCalculator.contribution(
                input("ACTIVE", "40.00", "10000000.00", TODAY.minusDays(30), "20.00", "75.00", "500000.00", 365, true, TODAY.plusDays(90)), TODAY);
        assertThat(c.lendableValue()).isEqualByComparingTo("5625000.00");
        assertThat(c.contribution()).isEqualByComparingTo("2250000.00");
        assertThat(c.zeroReason()).isNull();
    }

    @Test
    void staleValuationContributesNothingButStillShowsTheLendableValue() {
        Contribution c = ValuationCalculator.contribution(
                input("ACTIVE", "100.00", "1000000.00", TODAY.minusDays(400), "20.00", "75.00", "0.00", 365, false, null), TODAY);
        assertThat(c.zeroReason()).isEqualTo("VALUATION_STALE");
        assertThat(c.contribution()).isEqualByComparingTo("0.00");
        assertThat(c.lendableValue()).isEqualByComparingTo("600000.00");
    }

    @Test
    void valuationValidFromTheDayItIsDatedThroughTheLastValidDay() {
        assertThat(ValuationCalculator.valuationCurrent(TODAY.minusDays(365), 365, TODAY)).isTrue();
        assertThat(ValuationCalculator.valuationCurrent(TODAY.minusDays(366), 365, TODAY)).isFalse();
        assertThat(ValuationCalculator.valuationCurrent(TODAY.minusDays(9000), 0, TODAY)).isTrue();   // 0 = never stale
        assertThat(ValuationCalculator.valuationCurrent(null, 365, TODAY)).isFalse();
    }

    @Test
    void expiredInsuranceZeroesTheContributionOnlyWhenTheTypeRequiresIt() {
        Input expired = input("ACTIVE", "100.00", "1000000.00", TODAY, "20.00", "75.00", "0.00", 365, true, TODAY.minusDays(1));
        assertThat(ValuationCalculator.contribution(expired, TODAY).zeroReason()).isEqualTo("INSURANCE_EXPIRED");
        Input notRequired = input("ACTIVE", "100.00", "1000000.00", TODAY, "20.00", "75.00", "0.00", 365, false, TODAY.minusDays(1));
        assertThat(ValuationCalculator.contribution(notRequired, TODAY).zeroReason()).isNull();
        Input missing = input("ACTIVE", "100.00", "1000000.00", TODAY, "20.00", "75.00", "0.00", 365, true, null);
        assertThat(ValuationCalculator.contribution(missing, TODAY).zeroReason()).isEqualTo("INSURANCE_EXPIRED");
    }

    @Test
    void inactiveCollateralAndMissingValuationContributeNothing() {
        assertThat(ValuationCalculator.contribution(input("SUSPENDED", "100.00", "1000000.00", TODAY, "20.00", "75.00", "0.00", 365, false, null), TODAY)
                .zeroReason()).isEqualTo("COLLATERAL_NOT_ACTIVE");
        assertThat(ValuationCalculator.contribution(input("ACTIVE", "100.00", null, null, "20.00", "75.00", "0.00", 365, false, null), TODAY)
                .zeroReason()).isEqualTo("NO_APPROVED_VALUATION");
    }

    @Test
    void priorChargeLargerThanTheValueLeavesNoEquity() {
        Contribution c = ValuationCalculator.contribution(
                input("ACTIVE", "100.00", "1000000.00", TODAY, "20.00", "75.00", "900000.00", 365, false, null), TODAY);
        assertThat(c.zeroReason()).isEqualTo("NO_EQUITY");
        assertThat(c.contribution()).isEqualByComparingTo("0.00");
    }
}
