package com.bank.lending.lcm.facility;

import com.bank.lending.lcm.common.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Pure availability rules, no I/O. A drawdown must fit at the facility and at every ancestor.
 *
 * <pre>
 *   effective limit = sanctioned + active temporary excess, capped by drawing power when the facility is dp-governed
 *   consumed        = utilised (revolving: repayments restore the limit) or drawn_total (non-revolving: they do not)
 *   available       = effective limit - consumed - reserved
 * </pre>
 */
public final class AvailabilityCalculator {

    private AvailabilityCalculator() {}

    /** A facility with the context needed to evaluate it. drawingPower is only used when facility.dpGoverned(). */
    public record Level(Facility facility, BigDecimal activeExcess, BigDecimal drawingPower) {}

    public record Rejection(String code, String message, String facilityRef) {}

    public static BigDecimal baseLimit(Level l) {
        return l.facility().sanctionedAmount().add(Money.nz(l.activeExcess()));
    }

    public static BigDecimal effectiveLimit(Level l) {
        BigDecimal base = baseLimit(l);
        if (l.facility().dpGoverned()) {
            BigDecimal dp = Money.nz(l.drawingPower());
            return Money.min(base, dp);
        }
        return base;
    }

    public static BigDecimal consumed(Facility f) {
        return f.revolving() ? f.utilised() : f.drawnTotal();
    }

    /** Raw headroom of this one facility; negative when over the limit. */
    public static BigDecimal available(Level l) {
        return effectiveLimit(l).subtract(consumed(l.facility())).subtract(l.facility().reserved());
    }

    /** Headroom that can really be drawn: the smallest non-negative headroom along the chain (leaf first). */
    public static BigDecimal effectiveAvailable(List<Level> chain) {
        BigDecimal min = null;
        for (Level l : chain) {
            BigDecimal a = Money.max0(available(l));
            min = min == null ? a : Money.min(min, a);
        }
        return min == null ? Money.ZERO : min;
    }

    /**
     * First rule that would reject a drawdown of {@code amount}, or null when it fits.
     * The chain is ordered leaf first; status, validity and branch are checked on every level.
     */
    public static Rejection firstRejection(List<Level> chain, BigDecimal amount, String currency, String branch, LocalDate asOf) {
        for (Level l : chain) {
            Facility f = l.facility();
            if (!f.status().allowsDrawdown()) {
                return new Rejection("FACILITY_NOT_ACTIVE", "Facility " + f.facilityRef() + " is " + f.status(), f.facilityRef());
            }
            if (f.effectiveFrom() != null && asOf.isBefore(f.effectiveFrom())) {
                return new Rejection("FACILITY_NOT_EFFECTIVE", "Facility " + f.facilityRef() + " is effective from " + f.effectiveFrom(), f.facilityRef());
            }
            if (f.expiryDate() != null && asOf.isAfter(f.expiryDate())) {
                return new Rejection("FACILITY_EXPIRED", "Facility " + f.facilityRef() + " expired on " + f.expiryDate(), f.facilityRef());
            }
            if (!f.currencyCode().equalsIgnoreCase(currency)) {
                return new Rejection("CURRENCY_MISMATCH", "Facility " + f.facilityRef() + " is in " + f.currencyCode() + ", request is " + currency, f.facilityRef());
            }
            if (f.branchCode() != null && (branch == null || !f.branchCode().equalsIgnoreCase(branch))) {
                return new Rejection("BRANCH_NOT_ALLOWED", "Facility " + f.facilityRef() + " is restricted to branch " + f.branchCode(), f.facilityRef());
            }
            if (f.maxSingleAmount() != null && amount.compareTo(f.maxSingleAmount()) > 0) {
                return new Rejection("EXCEEDS_SINGLE_LIMIT", "Amount exceeds the single-drawdown cap " + f.maxSingleAmount() + " on " + f.facilityRef(), f.facilityRef());
            }
        }
        for (Level l : chain) {
            BigDecimal headroom = available(l);
            if (amount.compareTo(headroom) > 0) {
                boolean dpBinding = l.facility().dpGoverned() && Money.nz(l.drawingPower()).compareTo(baseLimit(l)) < 0;
                String code = dpBinding ? "DRAWING_POWER_SHORTFALL" : "LIMIT_EXCEEDED";
                return new Rejection(code, "Available " + Money.max0(headroom) + " on " + l.facility().facilityRef() + " is less than requested " + amount, l.facility().facilityRef());
            }
        }
        return null;
    }
}
