package com.bank.lending.chit.domain.rules;

import com.bank.lending.chit.common.Money;
import com.bank.lending.chit.domain.AllocationBucket;
import com.bank.lending.chit.domain.AuctionType;
import com.bank.lending.chit.domain.DividendBeneficiaries;
import com.bank.lending.chit.domain.DividendMode;
import com.bank.lending.chit.domain.ForemanGuaranteeMode;
import com.bank.lending.chit.domain.Frequency;
import com.bank.lending.chit.domain.LastCycleCommissionRule;
import com.bank.lending.chit.domain.NoBidRule;
import com.bank.lending.chit.domain.PayoutLiquidityRule;
import com.bank.lending.chit.domain.PrizedSubscriptionRule;
import com.bank.lending.chit.domain.QuorumRule;
import com.bank.lending.chit.domain.SecurityRule;
import com.bank.lending.chit.domain.TieBreakRule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Every rule of one chit group, as data. The values are frozen into a versioned snapshot when the group is activated
 * (Phase 2); after that they never change. Fields are boxed so that a missing value can be reported by
 * {@link RulesValidator} instead of failing with a null pointer. Real-world limits are marked TODO-VERIFY and are
 * always supplied by configuration, never hard-coded.
 *
 * <p>Percentages are written as 5 for 5%. Amounts are rupees with two decimals.
 */
public record ChitRules(
        BigDecimal chitValue,
        Integer ticketCount,
        Integer cycleCount,
        BigDecimal subscription,
        Frequency frequency,
        Integer instalmentDueDay,
        Integer auctionDay,
        LocalDate startDate,
        BigDecimal commissionPct,
        BigDecimal maxCommissionPct,
        BigDecimal minDiscountPct,
        BigDecimal maxDiscountPct,
        AuctionType auctionType,
        TieBreakRule tieBreakRule,
        NoBidRule noBidRule,
        BigDecimal foremanDefaultDiscountPct,
        DividendBeneficiaries dividendBeneficiaries,
        DividendMode dividendMode,
        PrizedSubscriptionRule prizedSubscriptionRule,
        LastCycleCommissionRule lastCycleCommissionRule,
        PayoutLiquidityRule payoutLiquidityRule,
        Integer graceDays,
        LateFeeRule lateFeeRule,
        DefaultThreshold defaultThreshold,
        QuorumRule auctionQuorumRule,
        BigDecimal quorumMinCollectionPct,
        SecurityRule securityRule,
        BigDecimal payoutHoldPct,
        BigDecimal enrolmentFee,
        BigDecimal registrationFee,
        ForemanGuaranteeMode foremanGuaranteeMode,
        List<AllocationBucket> allocationOrder,
        Boolean defaulterLosesDividend) {

    public static Builder builder() {
        return new Builder();
    }

    /** A copy of these rules as a builder, for changing one or two fields. */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.chitValue = chitValue;
        b.ticketCount = ticketCount;
        b.cycleCount = cycleCount;
        b.subscription = subscription;
        b.frequency = frequency;
        b.instalmentDueDay = instalmentDueDay;
        b.auctionDay = auctionDay;
        b.startDate = startDate;
        b.commissionPct = commissionPct;
        b.maxCommissionPct = maxCommissionPct;
        b.minDiscountPct = minDiscountPct;
        b.maxDiscountPct = maxDiscountPct;
        b.auctionType = auctionType;
        b.tieBreakRule = tieBreakRule;
        b.noBidRule = noBidRule;
        b.foremanDefaultDiscountPct = foremanDefaultDiscountPct;
        b.dividendBeneficiaries = dividendBeneficiaries;
        b.dividendMode = dividendMode;
        b.prizedSubscriptionRule = prizedSubscriptionRule;
        b.lastCycleCommissionRule = lastCycleCommissionRule;
        b.payoutLiquidityRule = payoutLiquidityRule;
        b.graceDays = graceDays;
        b.lateFeeRule = lateFeeRule;
        b.defaultThreshold = defaultThreshold;
        b.auctionQuorumRule = auctionQuorumRule;
        b.quorumMinCollectionPct = quorumMinCollectionPct;
        b.securityRule = securityRule;
        b.payoutHoldPct = payoutHoldPct;
        b.enrolmentFee = enrolmentFee;
        b.registrationFee = registrationFee;
        b.foremanGuaranteeMode = foremanGuaranteeMode;
        b.allocationOrder = allocationOrder;
        b.defaulterLosesDividend = defaulterLosesDividend;
        return b;
    }

    /** Fields not set keep the defaults below; the amounts and dates have no default and must be set. */
    public static final class Builder {
        private BigDecimal chitValue;
        private Integer ticketCount;
        private Integer cycleCount;
        private BigDecimal subscription;
        private Frequency frequency = Frequency.MONTHLY;
        private Integer instalmentDueDay;
        private Integer auctionDay;
        private LocalDate startDate;
        private BigDecimal commissionPct;
        private BigDecimal maxCommissionPct;
        private BigDecimal minDiscountPct;
        private BigDecimal maxDiscountPct;
        private AuctionType auctionType = AuctionType.OPEN_ASCENDING_DISCOUNT;
        private TieBreakRule tieBreakRule = TieBreakRule.EARLIEST_BID_TIME;
        private NoBidRule noBidRule = NoBidRule.LOTTERY;
        private BigDecimal foremanDefaultDiscountPct;
        private DividendBeneficiaries dividendBeneficiaries = DividendBeneficiaries.ALL_TICKETS;
        private DividendMode dividendMode = DividendMode.ADJUST_NEXT_INSTALMENT;
        private PrizedSubscriptionRule prizedSubscriptionRule = PrizedSubscriptionRule.FULL_SUBSCRIPTION;
        private LastCycleCommissionRule lastCycleCommissionRule = LastCycleCommissionRule.NONE;
        private PayoutLiquidityRule payoutLiquidityRule = PayoutLiquidityRule.BLOCK_UNTIL_FUNDED;
        private Integer graceDays = 0;
        private LateFeeRule lateFeeRule = LateFeeRule.none();
        private DefaultThreshold defaultThreshold = DefaultThreshold.ofMissedInstalments(3);
        private QuorumRule auctionQuorumRule = QuorumRule.ALL_PAID;
        private BigDecimal quorumMinCollectionPct;
        private SecurityRule securityRule = SecurityRule.NONE;
        private BigDecimal payoutHoldPct = Money.ZERO;
        private BigDecimal enrolmentFee = Money.ZERO;
        private BigDecimal registrationFee = Money.ZERO;
        private ForemanGuaranteeMode foremanGuaranteeMode = ForemanGuaranteeMode.NONE;
        private List<AllocationBucket> allocationOrder = List.of(AllocationBucket.LATE_FEES, AllocationBucket.ARREARS, AllocationBucket.CURRENT);
        private Boolean defaulterLosesDividend = Boolean.TRUE;

        public Builder chitValue(BigDecimal v) {
            this.chitValue = v;
            return this;
        }

        public Builder ticketCount(Integer v) {
            this.ticketCount = v;
            return this;
        }

        public Builder cycleCount(Integer v) {
            this.cycleCount = v;
            return this;
        }

        public Builder subscription(BigDecimal v) {
            this.subscription = v;
            return this;
        }

        public Builder frequency(Frequency v) {
            this.frequency = v;
            return this;
        }

        public Builder instalmentDueDay(Integer v) {
            this.instalmentDueDay = v;
            return this;
        }

        public Builder auctionDay(Integer v) {
            this.auctionDay = v;
            return this;
        }

        public Builder startDate(LocalDate v) {
            this.startDate = v;
            return this;
        }

        public Builder commissionPct(BigDecimal v) {
            this.commissionPct = v;
            return this;
        }

        public Builder maxCommissionPct(BigDecimal v) {
            this.maxCommissionPct = v;
            return this;
        }

        public Builder minDiscountPct(BigDecimal v) {
            this.minDiscountPct = v;
            return this;
        }

        public Builder maxDiscountPct(BigDecimal v) {
            this.maxDiscountPct = v;
            return this;
        }

        public Builder auctionType(AuctionType v) {
            this.auctionType = v;
            return this;
        }

        public Builder tieBreakRule(TieBreakRule v) {
            this.tieBreakRule = v;
            return this;
        }

        public Builder noBidRule(NoBidRule v) {
            this.noBidRule = v;
            return this;
        }

        public Builder foremanDefaultDiscountPct(BigDecimal v) {
            this.foremanDefaultDiscountPct = v;
            return this;
        }

        public Builder dividendBeneficiaries(DividendBeneficiaries v) {
            this.dividendBeneficiaries = v;
            return this;
        }

        public Builder dividendMode(DividendMode v) {
            this.dividendMode = v;
            return this;
        }

        public Builder prizedSubscriptionRule(PrizedSubscriptionRule v) {
            this.prizedSubscriptionRule = v;
            return this;
        }

        public Builder lastCycleCommissionRule(LastCycleCommissionRule v) {
            this.lastCycleCommissionRule = v;
            return this;
        }

        public Builder payoutLiquidityRule(PayoutLiquidityRule v) {
            this.payoutLiquidityRule = v;
            return this;
        }

        public Builder graceDays(Integer v) {
            this.graceDays = v;
            return this;
        }

        public Builder lateFeeRule(LateFeeRule v) {
            this.lateFeeRule = v;
            return this;
        }

        public Builder defaultThreshold(DefaultThreshold v) {
            this.defaultThreshold = v;
            return this;
        }

        public Builder auctionQuorumRule(QuorumRule v) {
            this.auctionQuorumRule = v;
            return this;
        }

        public Builder quorumMinCollectionPct(BigDecimal v) {
            this.quorumMinCollectionPct = v;
            return this;
        }

        public Builder securityRule(SecurityRule v) {
            this.securityRule = v;
            return this;
        }

        public Builder payoutHoldPct(BigDecimal v) {
            this.payoutHoldPct = v;
            return this;
        }

        public Builder enrolmentFee(BigDecimal v) {
            this.enrolmentFee = v;
            return this;
        }

        public Builder registrationFee(BigDecimal v) {
            this.registrationFee = v;
            return this;
        }

        public Builder foremanGuaranteeMode(ForemanGuaranteeMode v) {
            this.foremanGuaranteeMode = v;
            return this;
        }

        public Builder allocationOrder(List<AllocationBucket> v) {
            this.allocationOrder = v;
            return this;
        }

        public Builder defaulterLosesDividend(Boolean v) {
            this.defaulterLosesDividend = v;
            return this;
        }

        public ChitRules build() {
            return new ChitRules(
                    chitValue,
                    ticketCount,
                    cycleCount,
                    subscription,
                    frequency,
                    instalmentDueDay,
                    auctionDay,
                    startDate,
                    commissionPct,
                    maxCommissionPct,
                    minDiscountPct,
                    maxDiscountPct,
                    auctionType,
                    tieBreakRule,
                    noBidRule,
                    foremanDefaultDiscountPct,
                    dividendBeneficiaries,
                    dividendMode,
                    prizedSubscriptionRule,
                    lastCycleCommissionRule,
                    payoutLiquidityRule,
                    graceDays,
                    lateFeeRule,
                    defaultThreshold,
                    auctionQuorumRule,
                    quorumMinCollectionPct,
                    securityRule,
                    payoutHoldPct,
                    enrolmentFee,
                    registrationFee,
                    foremanGuaranteeMode,
                    allocationOrder,
                    defaulterLosesDividend);
        }
    }
}
