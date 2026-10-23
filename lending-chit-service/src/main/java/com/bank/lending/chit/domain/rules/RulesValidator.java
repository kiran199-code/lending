package com.bank.lending.chit.domain.rules;

import com.bank.lending.chit.common.ConfigInvalidException;
import com.bank.lending.chit.common.Money;
import com.bank.lending.chit.common.RuleViolation;
import com.bank.lending.chit.domain.AllocationBucket;
import com.bank.lending.chit.domain.AuctionType;
import com.bank.lending.chit.domain.Frequency;
import com.bank.lending.chit.domain.NoBidRule;
import com.bank.lending.chit.domain.QuorumRule;
import com.bank.lending.chit.domain.SecurityRule;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Rejects a configuration that does not reconcile. Every problem is reported with its own code, all at once, so a
 * group wizard can show them next to the right fields.
 *
 * <p>Codes: CONFIG_REQUIRED, TICKET_COUNT_INVALID, CYCLE_COUNT_MISMATCH, AMOUNT_NOT_POSITIVE, SUBSCRIPTION_MISMATCH,
 * PCT_OUT_OF_RANGE, COMMISSION_ABOVE_CAP, DISCOUNT_BOUNDS_INVALID, DISCOUNT_BELOW_COMMISSION, DAY_OUT_OF_RANGE,
 * AUCTION_BEFORE_DUE_DAY, GRACE_DAYS_INVALID, LATE_FEE_RULE_INVALID, DEFAULT_THRESHOLD_INVALID, QUORUM_PCT_INVALID,
 * ALLOCATION_ORDER_INVALID, FEE_NEGATIVE, UNSUPPORTED_COMBINATION.
 */
public final class RulesValidator {

    private RulesValidator() {
    }

    public static void validateOrThrow(ChitRules r) {
        List<RuleViolation> v = validate(r);
        if (!v.isEmpty()) {
            throw new ConfigInvalidException(v);
        }
    }

    public static List<RuleViolation> validate(ChitRules r) {
        List<RuleViolation> out = new ArrayList<>();
        if (r == null) {
            out.add(new RuleViolation("CONFIG_REQUIRED", "rules", "Rules are required"));
            return out;
        }
        requireAll(r, out);
        if (!out.isEmpty()) {
            return out; // the checks below need every field
        }

        // counts and amounts
        if (r.ticketCount() < 2) {
            out.add(v("TICKET_COUNT_INVALID", "ticketCount", "A chit needs at least 2 tickets"));
        }
        if (!r.cycleCount().equals(r.ticketCount())) {
            out.add(v("CYCLE_COUNT_MISMATCH", "cycleCount",
                    "cycleCount (" + r.cycleCount() + ") must equal ticketCount (" + r.ticketCount()
                            + ") so that every ticket can win exactly once"));
        }
        boolean amountsOk = true;
        if (!Money.isPositive(r.chitValue())) {
            out.add(v("AMOUNT_NOT_POSITIVE", "chitValue", "The chit value must be greater than zero"));
            amountsOk = false;
        }
        if (!Money.isPositive(r.subscription())) {
            out.add(v("AMOUNT_NOT_POSITIVE", "subscription", "The subscription must be greater than zero"));
            amountsOk = false;
        }
        if (amountsOk && r.ticketCount() > 0
                && r.subscription().multiply(BigDecimal.valueOf(r.ticketCount())).compareTo(r.chitValue()) != 0) {
            out.add(v("SUBSCRIPTION_MISMATCH", "subscription",
                    "subscription x ticketCount = " + r.subscription().multiply(BigDecimal.valueOf(r.ticketCount()))
                            .toPlainString() + " but the chit value is " + r.chitValue().toPlainString()));
        }

        // percentages
        pct(out, "commissionPct", r.commissionPct(), true);
        pct(out, "maxCommissionPct", r.maxCommissionPct(), true);
        pct(out, "minDiscountPct", r.minDiscountPct(), true);
        pct(out, "maxDiscountPct", r.maxDiscountPct(), true);
        if (r.commissionPct().compareTo(r.maxCommissionPct()) > 0) {
            out.add(v("COMMISSION_ABOVE_CAP", "commissionPct",
                    "Commission " + r.commissionPct().toPlainString() + "% is above the cap of "
                            + r.maxCommissionPct().toPlainString() + "%"));
        }
        if (r.minDiscountPct().compareTo(r.maxDiscountPct()) > 0) {
            out.add(v("DISCOUNT_BOUNDS_INVALID", "minDiscountPct",
                    "The minimum discount is above the maximum discount"));
        }
        if (r.minDiscountPct().compareTo(r.commissionPct()) < 0) {
            out.add(v("DISCOUNT_BELOW_COMMISSION", "minDiscountPct",
                    "The minimum discount (" + r.minDiscountPct().toPlainString() + "%) is below the commission ("
                            + r.commissionPct().toPlainString()
                            + "%); a winning bid at the minimum would make the dividend pool negative"));
        }

        // calendar
        int maxDay = r.frequency() == Frequency.MONTHLY ? 31 : 7;
        boolean daysOk = true;
        if (r.instalmentDueDay() < 1 || r.instalmentDueDay() > maxDay) {
            out.add(v("DAY_OUT_OF_RANGE", "instalmentDueDay", "Must be between 1 and " + maxDay + " for " + r.frequency()));
            daysOk = false;
        }
        if (r.auctionDay() < 1 || r.auctionDay() > maxDay) {
            out.add(v("DAY_OUT_OF_RANGE", "auctionDay", "Must be between 1 and " + maxDay + " for " + r.frequency()));
            daysOk = false;
        }
        if (daysOk && r.auctionDay() < r.instalmentDueDay()) {
            out.add(v("AUCTION_BEFORE_DUE_DAY", "auctionDay", "The auction day (" + r.auctionDay()
                    + ") is before the instalment due day (" + r.instalmentDueDay() + ")"));
        }

        // late fee, grace, default
        if (r.graceDays() < 0) {
            out.add(v("GRACE_DAYS_INVALID", "graceDays", "Grace days cannot be negative"));
        }
        lateFee(r.lateFeeRule(), out);
        DefaultThreshold t = r.defaultThreshold();
        boolean missedOk = t.missedInstalments() != null && t.missedInstalments() > 0;
        boolean daysThresholdOk = t.daysOverdue() != null && t.daysOverdue() > 0;
        if (!missedOk && !daysThresholdOk) {
            out.add(v("DEFAULT_THRESHOLD_INVALID", "defaultThreshold",
                    "Set the number of missed instalments, the days overdue, or both (greater than zero)"));
        }
        if (t.missedInstalments() != null && t.missedInstalments() <= 0
                || t.daysOverdue() != null && t.daysOverdue() <= 0) {
            out.add(v("DEFAULT_THRESHOLD_INVALID", "defaultThreshold", "A threshold that is set must be greater than zero"));
        }

        // quorum, hold, fees
        if (r.auctionQuorumRule() == QuorumRule.MIN_COLLECTION_PCT) {
            BigDecimal q = r.quorumMinCollectionPct();
            if (q == null || q.signum() <= 0 || q.compareTo(Money.HUNDRED) > 0) {
                out.add(v("QUORUM_PCT_INVALID", "quorumMinCollectionPct",
                        "MIN_COLLECTION_PCT needs a percentage above 0 and up to 100"));
            }
        }
        BigDecimal hold = r.payoutHoldPct();
        if (hold.signum() < 0 || hold.compareTo(Money.HUNDRED) >= 0) {
            out.add(v("PCT_OUT_OF_RANGE", "payoutHoldPct", "Must be at least 0 and below 100"));
        }
        if (r.enrolmentFee().signum() < 0) {
            out.add(v("FEE_NEGATIVE", "enrolmentFee", "A fee cannot be negative"));
        }
        if (r.registrationFee().signum() < 0) {
            out.add(v("FEE_NEGATIVE", "registrationFee", "A fee cannot be negative"));
        }

        // security deposit: a percentage of the chit value, needed only for the deposit-based rules
        BigDecimal dep = r.securityDepositPct();
        boolean depositRule = r.securityRule() == SecurityRule.DEPOSIT || r.securityRule() == SecurityRule.LIEN_ON_DEPOSIT;
        if (dep.signum() < 0 || dep.compareTo(Money.HUNDRED) > 0) {
            out.add(v("PCT_OUT_OF_RANGE", "securityDepositPct", "Must be between 0 and 100"));
        } else if (depositRule && dep.signum() == 0) {
            out.add(v("CONFIG_REQUIRED", "securityDepositPct", r.securityRule() + " needs a security deposit percentage above 0"));
        } else if (!depositRule && dep.signum() > 0) {
            out.add(v("UNSUPPORTED_COMBINATION", "securityDepositPct",
                    "A security deposit percentage only applies to DEPOSIT or LIEN_ON_DEPOSIT, not " + r.securityRule()));
        }

        // allocation order
        Set<AllocationBucket> expected = EnumSet.of(AllocationBucket.LATE_FEES, AllocationBucket.ARREARS, AllocationBucket.CURRENT);
        List<AllocationBucket> order = r.allocationOrder();
        if (order.size() != 3 || !EnumSet.copyOf(order).equals(expected)) {
            out.add(v("ALLOCATION_ORDER_INVALID", "allocationOrder",
                    "Must list LATE_FEES, ARREARS and CURRENT once each (ADVANCE is always last)"));
        }

        // combinations
        boolean noBidMakesSense = r.auctionType() == AuctionType.OPEN_ASCENDING_DISCOUNT || r.auctionType() == AuctionType.SEALED_BID;
        if (r.noBidRule() == NoBidRule.ROLL_OVER_CYCLE && !noBidMakesSense) {
            out.add(v("UNSUPPORTED_COMBINATION", "noBidRule",
                    "ROLL_OVER_CYCLE needs a bidding auction; " + r.auctionType() + " has no bids"));
        }
        if (r.noBidRule() == NoBidRule.FOREMAN_DEFAULT_DISCOUNT) {
            BigDecimal d = r.foremanDefaultDiscountPct();
            if (d == null) {
                out.add(v("CONFIG_REQUIRED", "foremanDefaultDiscountPct",
                        "FOREMAN_DEFAULT_DISCOUNT needs foremanDefaultDiscountPct"));
            } else if (d.compareTo(r.minDiscountPct()) < 0 || d.compareTo(r.maxDiscountPct()) > 0
                    || d.compareTo(r.commissionPct()) < 0) {
                out.add(v("UNSUPPORTED_COMBINATION", "foremanDefaultDiscountPct",
                        "The default discount must lie within the discount bounds and not be below the commission"));
            }
        }
        return out;
    }

    private static void requireAll(ChitRules r, List<RuleViolation> out) {
        req(out, "chitValue", r.chitValue());
        req(out, "ticketCount", r.ticketCount());
        req(out, "cycleCount", r.cycleCount());
        req(out, "subscription", r.subscription());
        req(out, "frequency", r.frequency());
        req(out, "instalmentDueDay", r.instalmentDueDay());
        req(out, "auctionDay", r.auctionDay());
        req(out, "startDate", r.startDate());
        req(out, "commissionPct", r.commissionPct());
        req(out, "maxCommissionPct", r.maxCommissionPct());
        req(out, "minDiscountPct", r.minDiscountPct());
        req(out, "maxDiscountPct", r.maxDiscountPct());
        req(out, "auctionType", r.auctionType());
        req(out, "tieBreakRule", r.tieBreakRule());
        req(out, "noBidRule", r.noBidRule());
        req(out, "dividendBeneficiaries", r.dividendBeneficiaries());
        req(out, "dividendMode", r.dividendMode());
        req(out, "prizedSubscriptionRule", r.prizedSubscriptionRule());
        req(out, "lastCycleCommissionRule", r.lastCycleCommissionRule());
        req(out, "payoutLiquidityRule", r.payoutLiquidityRule());
        req(out, "graceDays", r.graceDays());
        req(out, "lateFeeRule", r.lateFeeRule());
        req(out, "defaultThreshold", r.defaultThreshold());
        req(out, "auctionQuorumRule", r.auctionQuorumRule());
        req(out, "securityRule", r.securityRule());
        req(out, "payoutHoldPct", r.payoutHoldPct());
        req(out, "enrolmentFee", r.enrolmentFee());
        req(out, "registrationFee", r.registrationFee());
        req(out, "foremanGuaranteeMode", r.foremanGuaranteeMode());
        req(out, "allocationOrder", r.allocationOrder());
        req(out, "defaulterLosesDividend", r.defaulterLosesDividend());
        req(out, "securityDepositPct", r.securityDepositPct());
    }

    private static void req(List<RuleViolation> out, String field, Object value) {
        if (value == null) {
            out.add(v("CONFIG_REQUIRED", field, field + " is required"));
        }
    }

    private static void pct(List<RuleViolation> out, String field, BigDecimal value, boolean allowZero) {
        boolean bad = value.signum() < 0 || value.compareTo(Money.HUNDRED) > 0 || (!allowZero && value.signum() == 0);
        if (bad) {
            out.add(v("PCT_OUT_OF_RANGE", field, field + " must be between 0 and 100"));
        }
    }

    private static void lateFee(LateFeeRule rule, List<RuleViolation> out) {
        switch (rule.kind()) {
            case NONE -> { }
            case FLAT -> {
                if (!Money.isPositive(rule.flatAmount())) {
                    out.add(v("LATE_FEE_RULE_INVALID", "lateFeeRule", "A flat late fee needs an amount above zero"));
                }
            }
            case PERCENT_PER_DAY -> {
                if (!Money.isPositive(rule.pctPerDay()) || rule.pctPerDay().compareTo(Money.HUNDRED) > 0) {
                    out.add(v("LATE_FEE_RULE_INVALID", "lateFeeRule", "A per-day late fee needs a percentage above 0 and up to 100"));
                }
                if (rule.capAmount() == null && rule.capPctOfOverdue() == null) {
                    out.add(v("LATE_FEE_RULE_INVALID", "lateFeeRule", "A per-day late fee needs a cap (amount, percentage of the overdue amount, or both)"));
                }
            }
        }
        if (rule.capAmount() != null && rule.capAmount().signum() <= 0) {
            out.add(v("LATE_FEE_RULE_INVALID", "lateFeeRule", "The cap amount must be above zero"));
        }
        if (rule.capPctOfOverdue() != null
                && (rule.capPctOfOverdue().signum() <= 0 || rule.capPctOfOverdue().compareTo(Money.HUNDRED) > 0)) {
            out.add(v("LATE_FEE_RULE_INVALID", "lateFeeRule", "The cap percentage must be above 0 and up to 100"));
        }
    }

    private static RuleViolation v(String code, String field, String message) {
        return new RuleViolation(code, field, message);
    }
}
