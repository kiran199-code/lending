package com.bank.lending.lcm.collateral;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationRulesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

    @Test
    void everythingInOrderHasNothingMissing() {
        List<String> unmet = ActivationRules.unmet("VERIFIED", true, TODAY.minusDays(10), 365, true, true, true, TODAY.plusDays(30), TODAY);
        assertThat(unmet).isEmpty();
    }

    @Test
    void reportsEachMissingRequirement() {
        List<String> unmet = ActivationRules.unmet("PENDING", false, null, 365, true, false, true, null, TODAY);
        assertThat(unmet).containsExactly("LEGAL_NOT_VERIFIED", "NO_CURRENT_VALUATION", "CHARGE_NOT_REGISTERED", "INSURANCE_MISSING_OR_EXPIRED");
    }

    @Test
    void staleValuationCountsAsMissing() {
        List<String> unmet = ActivationRules.unmet("VERIFIED", true, TODAY.minusDays(500), 365, false, false, false, null, TODAY);
        assertThat(unmet).containsExactly("NO_CURRENT_VALUATION");
    }

    @Test
    void typesWithoutChargeOrInsuranceNeedNeither() {
        List<String> unmet = ActivationRules.unmet("VERIFIED", true, TODAY, 30, false, false, false, null, TODAY);
        assertThat(unmet).isEmpty();
    }
}
