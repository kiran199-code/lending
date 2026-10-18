package com.bank.lending.lcm.collateral;

import com.bank.lending.lcm.common.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Pure collateral valuation rules.
 *
 * <pre>
 *   eligible value = max(0, base value x (1 - haircut%) - prior charge)
 *   lendable value = eligible value x max LTV%
 *   contribution   = lendable value x allocation% to the facility
 * </pre>
 * A collateral contributes zero unless it is ACTIVE, has an approved valuation that has not gone stale and,
 * where the type requires it, valid insurance.
 */
public final class ValuationCalculator {

    private ValuationCalculator() {}

    public record Input(String collateralRef, String collateralStatus, BigDecimal allocationPct, BigDecimal baseValue,
                        LocalDate valuationDate, BigDecimal haircutPct, BigDecimal maxLtvPct, BigDecimal priorCharge,
                        int validityDays, boolean requiresInsurance, LocalDate insuranceExpiry) {}

    public record Contribution(String collateralRef, BigDecimal allocationPct, BigDecimal baseValue, BigDecimal haircutPct,
                               BigDecimal priorCharge, BigDecimal eligibleValue, BigDecimal maxLtvPct, BigDecimal lendableValue,
                               BigDecimal contribution, String zeroReason) {}

    public static BigDecimal eligibleValue(BigDecimal base, BigDecimal haircutPct, BigDecimal priorCharge) {
        BigDecimal afterHaircut = base.multiply(Money.HUNDRED.subtract(haircutPct)).divide(Money.HUNDRED, 2, RoundingMode.HALF_UP);
        return Money.max0(afterHaircut.subtract(Money.nz(priorCharge)));
    }

    public static BigDecimal lendableValue(BigDecimal eligible, BigDecimal maxLtvPct) {
        return eligible.multiply(maxLtvPct).divide(Money.HUNDRED, 2, RoundingMode.HALF_UP);
    }

    public static boolean valuationCurrent(LocalDate valuationDate, int validityDays, LocalDate asOf) {
        if (valuationDate == null) return false;
        return validityDays == 0 || !asOf.isAfter(valuationDate.plusDays(validityDays));
    }

    public static boolean insuranceValid(boolean required, LocalDate expiry, LocalDate asOf) {
        return !required || (expiry != null && !expiry.isBefore(asOf));
    }

    public static Contribution contribution(Input in, LocalDate asOf) {
        String reason = null;
        if (!"ACTIVE".equals(in.collateralStatus())) reason = "COLLATERAL_NOT_ACTIVE";
        else if (in.baseValue() == null) reason = "NO_APPROVED_VALUATION";
        else if (!valuationCurrent(in.valuationDate(), in.validityDays(), asOf)) reason = "VALUATION_STALE";
        else if (!insuranceValid(in.requiresInsurance(), in.insuranceExpiry(), asOf)) reason = "INSURANCE_EXPIRED";

        BigDecimal eligible = Money.ZERO;
        BigDecimal lendable = Money.ZERO;
        BigDecimal contribution = Money.ZERO;
        if (in.baseValue() != null) {
            eligible = eligibleValue(in.baseValue(), in.haircutPct(), in.priorCharge());
            lendable = lendableValue(eligible, in.maxLtvPct());
        }
        if (reason == null) {
            contribution = lendable.multiply(in.allocationPct()).divide(Money.HUNDRED, 2, RoundingMode.HALF_UP);
            if (contribution.signum() == 0) reason = "NO_EQUITY";
        }
        return new Contribution(in.collateralRef(), in.allocationPct(), in.baseValue(), in.haircutPct(), Money.nz(in.priorCharge()),
                eligible, in.maxLtvPct(), lendable, contribution, reason);
    }
}
