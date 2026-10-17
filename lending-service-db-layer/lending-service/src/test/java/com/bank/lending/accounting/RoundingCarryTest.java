package com.bank.lending.accounting;

import com.bank.lending.accounting.engine.RoundingCarry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RoundingCarryTest {

    @Test
    void postsTheChangeInTheRoundedCumulativeSoNothingDrifts() {
        // 1,000,000.00 at 12.00% ACT/365 accrues 328.7671 a day (requirements section 8.4)
        BigDecimal daily = new BigDecimal("328.7671");
        BigDecimal cum = BigDecimal.ZERO;
        BigDecimal posted = BigDecimal.ZERO;
        String[] expectedDelta = {"328.77", "328.76", "328.77"};
        for (int day = 0; day < 3; day++) {
            RoundingCarry.Step s = RoundingCarry.apply(cum, posted, daily, 2);
            assertThat(s.delta()).isEqualByComparingTo(expectedDelta[day]);
            cum = s.cumulativeAccrued();
            posted = s.cumulativePosted();
        }
        assertThat(posted).isEqualByComparingTo("986.30");
        assertThat(cum).isEqualByComparingTo("986.3013");
    }

    @Test
    void aLongRunNeverLeavesAResidual() {
        BigDecimal daily = new BigDecimal("10.8493");
        BigDecimal cum = BigDecimal.ZERO;
        BigDecimal posted = BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (int day = 1; day <= 365; day++) {
            RoundingCarry.Step s = RoundingCarry.apply(cum, posted, daily, 2);
            sum = sum.add(s.delta());
            cum = s.cumulativeAccrued();
            posted = s.cumulativePosted();
        }
        assertThat(sum).isEqualByComparingTo(cum.setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(sum).isEqualByComparingTo("3959.99"); // 365 x 10.8493 = 3959.9945
    }

    @Test
    void aPenalAccrualOfTwentyThreeDaysMatchesTheDemandsRoundedAmount() {
        // requirements scenario 5: 23 x 10.8493 = 249.5339, posted total 249.53
        BigDecimal cum = BigDecimal.ZERO;
        BigDecimal posted = BigDecimal.ZERO;
        for (int day = 1; day <= 23; day++) {
            RoundingCarry.Step s = RoundingCarry.apply(cum, posted, new BigDecimal("10.8493"), 2);
            cum = s.cumulativeAccrued();
            posted = s.cumulativePosted();
        }
        assertThat(posted).isEqualByComparingTo("249.53");
    }

    @Test
    void zeroPrecisionCurrenciesRoundToWholeUnits() {
        RoundingCarry.Step s = RoundingCarry.apply(new BigDecimal("0.4"), BigDecimal.ZERO, new BigDecimal("0.4"), 0);
        assertThat(s.delta()).isEqualByComparingTo("1");
    }
}
