package com.bank.lending.chit.domain.rules;

/**
 * Fills the optional rules a caller left out with the same defaults the builder uses, so an API request only has to
 * state what differs from the usual. Required fields (amounts, counts, days, start date, commission and discount
 * limits) have no default and stay null, so the validator reports them.
 */
public final class RulesDefaults {

    private static final ChitRules D = ChitRules.builder().build();

    private RulesDefaults() {
    }

    public static ChitRules fill(ChitRules r) {
        if (r == null) {
            return null;
        }
        return new ChitRules(
                r.chitValue(), r.ticketCount(),
                r.cycleCount() != null ? r.cycleCount() : r.ticketCount(),
                r.subscription() != null ? r.subscription() : derivedSubscription(r),
                or(r.frequency(), D.frequency()),
                r.instalmentDueDay(), r.auctionDay(), r.startDate(),
                r.commissionPct(), r.maxCommissionPct(), r.minDiscountPct(), r.maxDiscountPct(),
                or(r.auctionType(), D.auctionType()),
                or(r.tieBreakRule(), D.tieBreakRule()),
                or(r.noBidRule(), D.noBidRule()),
                r.foremanDefaultDiscountPct(),
                or(r.dividendBeneficiaries(), D.dividendBeneficiaries()),
                or(r.dividendMode(), D.dividendMode()),
                or(r.prizedSubscriptionRule(), D.prizedSubscriptionRule()),
                or(r.lastCycleCommissionRule(), D.lastCycleCommissionRule()),
                or(r.payoutLiquidityRule(), D.payoutLiquidityRule()),
                or(r.graceDays(), D.graceDays()),
                or(r.lateFeeRule(), D.lateFeeRule()),
                or(r.defaultThreshold(), D.defaultThreshold()),
                or(r.auctionQuorumRule(), D.auctionQuorumRule()),
                r.quorumMinCollectionPct(),
                or(r.securityRule(), D.securityRule()),
                or(r.payoutHoldPct(), D.payoutHoldPct()),
                or(r.enrolmentFee(), D.enrolmentFee()),
                or(r.registrationFee(), D.registrationFee()),
                or(r.foremanGuaranteeMode(), D.foremanGuaranteeMode()),
                or(r.allocationOrder(), D.allocationOrder()),
                or(r.defaulterLosesDividend(), D.defaulterLosesDividend()),
                or(r.securityDepositPct(), D.securityDepositPct()));
    }

    /** The prompt's default: subscription is the chit value divided by the number of tickets, when that divides exactly. */
    private static java.math.BigDecimal derivedSubscription(ChitRules r) {
        if (r.chitValue() == null || r.ticketCount() == null || r.ticketCount() <= 0) {
            return null;
        }
        java.math.BigDecimal[] qr = r.chitValue().divideAndRemainder(java.math.BigDecimal.valueOf(r.ticketCount()));
        if (qr[1].signum() != 0) {
            return null; // does not divide exactly: leave it for the validator to report as required
        }
        return qr[0].setScale(2);
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }
}
