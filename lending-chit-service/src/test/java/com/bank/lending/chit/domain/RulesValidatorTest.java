package com.bank.lending.chit.domain;

import com.bank.lending.chit.common.ConfigInvalidException;
import com.bank.lending.chit.common.RuleViolation;
import com.bank.lending.chit.domain.rules.ChitRules;
import com.bank.lending.chit.domain.rules.DefaultThreshold;
import com.bank.lending.chit.domain.rules.LateFeeRule;
import com.bank.lending.chit.domain.rules.RulesEditPolicy;
import com.bank.lending.chit.domain.rules.RulesValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesValidatorTest {

    private static Set<String> codes(ChitRules r) {
        return RulesValidator.validate(r).stream().map(RuleViolation::code).collect(Collectors.toSet());
    }

    private static ChitRules.Builder b() {
        return TestRules.baseline();
    }

    @Test
    void theBaselineGroupIsValid() {
        assertTrue(RulesValidator.validate(TestRules.valid()).isEmpty());
        RulesValidator.validateOrThrow(TestRules.valid());
    }

    @Test
    void missingFieldsAreAllReportedTogether() {
        List<RuleViolation> v = RulesValidator.validate(ChitRules.builder().build());
        assertTrue(v.size() > 3);
        assertTrue(v.stream().allMatch(x -> x.code().equals("CONFIG_REQUIRED")));
    }

    @Test
    void nullRulesAreRefused() {
        assertEquals(Set.of("CONFIG_REQUIRED"), codes(null));
    }

    @Test
    void subscriptionTimesTicketsMustEqualTheChitValue() {
        assertTrue(codes(b().subscription(new BigDecimal("4999.99")).build()).contains("SUBSCRIPTION_MISMATCH"));
    }

    @Test
    void cycleCountMustEqualTicketCount() {
        assertTrue(codes(b().cycleCount(19).build()).contains("CYCLE_COUNT_MISMATCH"));
    }

    @Test
    void aChitNeedsAtLeastTwoTickets() {
        assertTrue(codes(b().ticketCount(1).cycleCount(1).subscription(new BigDecimal("100000.00")).build()).contains("TICKET_COUNT_INVALID"));
    }

    @Test
    void amountsMustBePositive() {
        Set<String> c = codes(b().chitValue(BigDecimal.ZERO).subscription(new BigDecimal("-1")).build());
        assertTrue(c.contains("AMOUNT_NOT_POSITIVE"));
    }

    @Test
    void commissionAboveTheCapIsRefused() {
        assertTrue(codes(b().commissionPct(new BigDecimal("6")).build()).contains("COMMISSION_ABOVE_CAP"));
    }

    @Test
    void percentagesMustBeBetweenZeroAndHundred() {
        assertTrue(codes(b().maxDiscountPct(new BigDecimal("101")).build()).contains("PCT_OUT_OF_RANGE"));
        assertTrue(codes(b().commissionPct(new BigDecimal("-1")).build()).contains("PCT_OUT_OF_RANGE"));
    }

    @Test
    void discountBoundsMustBeConsistent() {
        assertTrue(codes(b().minDiscountPct(new BigDecimal("30")).maxDiscountPct(new BigDecimal("20")).build())
                .contains("DISCOUNT_BOUNDS_INVALID"));
    }

    @Test
    void fixtureE_minimumDiscountBelowCommissionIsRefused() {
        // minimum 3% with commission 5%: a winning bid at the minimum would make the dividend pool negative
        assertTrue(codes(b().minDiscountPct(new BigDecimal("3")).build()).contains("DISCOUNT_BELOW_COMMISSION"));
    }

    @Test
    void auctionDayBeforeDueDayIsRefused() {
        assertTrue(codes(b().instalmentDueDay(15).auctionDay(10).build()).contains("AUCTION_BEFORE_DUE_DAY"));
        assertFalse(codes(b().instalmentDueDay(10).auctionDay(10).build()).contains("AUCTION_BEFORE_DUE_DAY"));
    }

    @Test
    void dayOfWeekRangeAppliesToWeeklyGroups() {
        assertTrue(codes(b().frequency(Frequency.WEEKLY).instalmentDueDay(5).auctionDay(10).build()).contains("DAY_OUT_OF_RANGE"));
        assertFalse(codes(b().frequency(Frequency.WEEKLY).instalmentDueDay(2).auctionDay(5).build()).contains("DAY_OUT_OF_RANGE"));
        assertTrue(codes(b().instalmentDueDay(0).build()).contains("DAY_OUT_OF_RANGE"));
        assertTrue(codes(b().auctionDay(32).build()).contains("DAY_OUT_OF_RANGE"));
    }

    @Test
    void lateFeeRulesAreChecked() {
        assertTrue(codes(b().lateFeeRule(LateFeeRule.flat(BigDecimal.ZERO)).build()).contains("LATE_FEE_RULE_INVALID"));
        // a per-day fee must have a cap
        assertTrue(codes(b().lateFeeRule(LateFeeRule.percentPerDay(new BigDecimal("0.1"), null, null)).build())
                .contains("LATE_FEE_RULE_INVALID"));
        assertTrue(codes(b().lateFeeRule(LateFeeRule.percentPerDay(new BigDecimal("0.1"), new BigDecimal("500"), null)).build()).isEmpty());
        assertTrue(codes(b().lateFeeRule(LateFeeRule.flat(new BigDecimal("100"))).build()).isEmpty());
    }

    @Test
    void graceDaysCannotBeNegative() {
        assertTrue(codes(b().graceDays(-1).build()).contains("GRACE_DAYS_INVALID"));
    }

    @Test
    void defaultThresholdNeedsAtLeastOneLimit() {
        assertTrue(codes(b().defaultThreshold(new DefaultThreshold(null, null)).build()).contains("DEFAULT_THRESHOLD_INVALID"));
        assertTrue(codes(b().defaultThreshold(new DefaultThreshold(0, 30)).build()).contains("DEFAULT_THRESHOLD_INVALID"));
        assertFalse(codes(b().defaultThreshold(new DefaultThreshold(null, 30)).build()).contains("DEFAULT_THRESHOLD_INVALID"));
    }

    @Test
    void minimumCollectionQuorumNeedsAPercentage() {
        assertTrue(codes(b().auctionQuorumRule(QuorumRule.MIN_COLLECTION_PCT).build()).contains("QUORUM_PCT_INVALID"));
        assertFalse(codes(b().auctionQuorumRule(QuorumRule.MIN_COLLECTION_PCT).quorumMinCollectionPct(new BigDecimal("90")).build())
                .contains("QUORUM_PCT_INVALID"));
    }

    @Test
    void holdAndFeesAreChecked() {
        assertTrue(codes(b().payoutHoldPct(new BigDecimal("100")).build()).contains("PCT_OUT_OF_RANGE"));
        assertTrue(codes(b().enrolmentFee(new BigDecimal("-1")).build()).contains("FEE_NEGATIVE"));
        assertTrue(codes(b().registrationFee(new BigDecimal("-1")).build()).contains("FEE_NEGATIVE"));
    }

    @Test
    void allocationOrderMustBeAPermutationOfThreeBuckets() {
        assertTrue(codes(b().allocationOrder(List.of(AllocationBucket.CURRENT, AllocationBucket.CURRENT, AllocationBucket.LATE_FEES)).build())
                .contains("ALLOCATION_ORDER_INVALID"));
        assertTrue(codes(b().allocationOrder(List.of(AllocationBucket.LATE_FEES, AllocationBucket.ARREARS)).build())
                .contains("ALLOCATION_ORDER_INVALID"));
        assertTrue(codes(b().allocationOrder(List.of(AllocationBucket.CURRENT, AllocationBucket.ARREARS, AllocationBucket.LATE_FEES)).build()).isEmpty());
    }

    @Test
    void rollOverNeedsABiddingAuction() {
        assertTrue(codes(b().auctionType(AuctionType.LOTTERY).noBidRule(NoBidRule.ROLL_OVER_CYCLE).build()).contains("UNSUPPORTED_COMBINATION"));
        assertTrue(codes(b().auctionType(AuctionType.SEALED_BID).noBidRule(NoBidRule.ROLL_OVER_CYCLE).build()).isEmpty());
    }

    @Test
    void foremanDefaultDiscountNeedsAValueInsideTheBounds() {
        assertTrue(codes(b().noBidRule(NoBidRule.FOREMAN_DEFAULT_DISCOUNT).build()).contains("CONFIG_REQUIRED"));
        assertTrue(codes(b().noBidRule(NoBidRule.FOREMAN_DEFAULT_DISCOUNT).foremanDefaultDiscountPct(new BigDecimal("50")).build())
                .contains("UNSUPPORTED_COMBINATION"));
        assertTrue(codes(b().noBidRule(NoBidRule.FOREMAN_DEFAULT_DISCOUNT).foremanDefaultDiscountPct(new BigDecimal("10")).build()).isEmpty());
    }

    @Test
    void validateOrThrowCarriesEveryViolation() {
        ConfigInvalidException e = assertThrows(ConfigInvalidException.class,
                () -> RulesValidator.validateOrThrow(b().subscription(new BigDecimal("1")).commissionPct(new BigDecimal("9")).build()));
        assertEquals("CONFIG_INVALID", e.code());
        Set<String> got = e.violations().stream().map(RuleViolation::code).collect(Collectors.toSet());
        assertTrue(got.contains("SUBSCRIPTION_MISMATCH"));
        assertTrue(got.contains("COMMISSION_ABOVE_CAP"));
    }

    @Test
    void rulesAreEditableOnlyBeforeTheGroupIsFull() {
        assertTrue(RulesEditPolicy.isEditable(GroupStatus.DRAFT));
        assertTrue(RulesEditPolicy.isEditable(GroupStatus.OPEN_FOR_ENROLMENT));
        for (GroupStatus s : new GroupStatus[]{GroupStatus.FULL, GroupStatus.ACTIVE, GroupStatus.CLOSING, GroupStatus.CLOSED, GroupStatus.CANCELLED}) {
            assertFalse(RulesEditPolicy.isEditable(s));
            assertEquals("RULES_FROZEN", assertThrows(com.bank.lending.chit.common.ChitException.class,
                    () -> RulesEditPolicy.assertEditable(s)).code());
        }
    }

    @Test
    void toBuilderChangesOneFieldAndKeepsTheRest() {
        ChitRules changed = TestRules.valid().toBuilder().graceDays(5).build();
        assertEquals(5, changed.graceDays());
        assertEquals(TestRules.valid().chitValue(), changed.chitValue());
    }
}
