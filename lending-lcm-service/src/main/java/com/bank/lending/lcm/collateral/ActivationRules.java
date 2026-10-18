package com.bank.lending.lcm.collateral;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Pure checks that must pass before a collateral can be ACTIVE and counted for drawing power. */
public final class ActivationRules {

    private ActivationRules() {}

    /** Returns the unmet requirements; empty means the collateral can be activated. */
    public static List<String> unmet(String legalStatus, boolean hasApprovedValuation, LocalDate latestValuationDate, int validityDays,
                                     boolean requiresCharge, boolean chargeRegistered, boolean requiresInsurance, LocalDate insuranceExpiry,
                                     LocalDate asOf) {
        List<String> missing = new ArrayList<>();
        if (!"VERIFIED".equals(legalStatus)) missing.add("LEGAL_NOT_VERIFIED");
        if (!hasApprovedValuation || !ValuationCalculator.valuationCurrent(latestValuationDate, validityDays, asOf)) {
            missing.add("NO_CURRENT_VALUATION");
        }
        if (requiresCharge && !chargeRegistered) missing.add("CHARGE_NOT_REGISTERED");
        if (!ValuationCalculator.insuranceValid(requiresInsurance, insuranceExpiry, asOf)) missing.add("INSURANCE_MISSING_OR_EXPIRED");
        return missing;
    }
}
