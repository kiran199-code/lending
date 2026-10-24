package com.bank.lending.chit.calc;

import com.bank.lending.chit.calc.AdvancePlanner.Slice;
import com.bank.lending.chit.calc.AdvancePlanner.Use;
import com.bank.lending.chit.calc.DefaultDetector.OverdueItem;
import com.bank.lending.chit.calc.DefaultDetector.Verdict;
import com.bank.lending.chit.domain.rules.DefaultThreshold;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure collection arithmetic: demand figures, default detection, advance use and the payment limit. */
class CollectionCalcTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    // ---- DemandFigures ----------------------------------------------------------------------------------------------

    @Test
    void aFreshDemandOwesTheWholeSubscription() {
        DemandFigures d = new DemandFigures(bd("5000"), null, null, null, null);
        eq("5000.00", d.subscriptionOutstanding());
        eq("0.00", d.lateFeeOutstanding());
        assertFalse(d.settled());
    }

    @Test
    void theDividendAdjustmentLowersWhatIsDue() {
        DemandFigures d = new DemandFigures(bd("5000"), bd("750"), null, null, null);
        eq("4250.00", d.subscriptionDue());
        eq("4250.00", d.subscriptionOutstanding());
        assertFalse(d.paySubscription(bd("4249.99")).settled());
        assertTrue(d.paySubscription(bd("4250")).settled());
    }

    @Test
    void aDividendThatCoversTheWholeInstalmentSettlesItAtOnce() {
        assertTrue(new DemandFigures(bd("5000"), bd("5000"), null, null, null).settled());
    }

    @Test
    void anUnpaidLateFeeKeepsTheDemandOpenEvenWhenTheSubscriptionIsPaid() {
        DemandFigures d = new DemandFigures(bd("5000"), null, bd("5000"), bd("120"), bd("100"));
        eq("0.00", d.subscriptionOutstanding());
        eq("20.00", d.lateFeeOutstanding());
        eq("20.00", d.totalOutstanding());
        assertFalse(d.settled());
        assertTrue(d.payLateFee(bd("20")).settled());
    }

    @Test
    void accruingAFeeReopensASettledDemand() {
        DemandFigures d = new DemandFigures(bd("5000"), null, bd("5000"), null, null);
        assertTrue(d.settled());
        assertFalse(d.accrueLateFee(bd("50")).settled());
    }

    // ---- DefaultDetector --------------------------------------------------------------------------------------------

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Test
    void anInstalmentInsideTheGraceIsNotMissed() {
        Verdict v = DefaultDetector.evaluate(DefaultThreshold.ofMissedInstalments(1), 5, TODAY,
                List.of(new OverdueItem(TODAY.minusDays(5), bd("5000"))));
        assertEquals(0, v.missedInstalments());
        assertFalse(v.breached());
        assertNull(v.reason());
    }

    @Test
    void oneDayPastTheGraceIsMissed() {
        Verdict v = DefaultDetector.evaluate(DefaultThreshold.ofMissedInstalments(1), 5, TODAY,
                List.of(new OverdueItem(TODAY.minusDays(6), bd("5000"))));
        assertEquals(1, v.missedInstalments());
        assertEquals(6, v.oldestDaysOverdue());
        assertTrue(v.breached());
        eq("5000.00", v.outstanding());
    }

    @Test
    void paidInstalmentsDoNotCount() {
        Verdict v = DefaultDetector.evaluate(DefaultThreshold.ofMissedInstalments(1), 0, TODAY,
                List.of(new OverdueItem(TODAY.minusDays(40), bd("0")), new OverdueItem(TODAY.minusDays(10), bd("0.00"))));
        assertEquals(0, v.missedInstalments());
        assertFalse(v.breached());
    }

    @Test
    void twoMissedInstalmentsNeedTwoToBreach() {
        DefaultThreshold t = DefaultThreshold.ofMissedInstalments(2);
        assertFalse(DefaultDetector.evaluate(t, 0, TODAY, List.of(new OverdueItem(TODAY.minusDays(30), bd("100")))).breached());
        Verdict v = DefaultDetector.evaluate(t, 0, TODAY, List.of(new OverdueItem(TODAY.minusDays(30), bd("100")),
                new OverdueItem(TODAY.minusDays(1), bd("200"))));
        assertTrue(v.breached());
        eq("300.00", v.outstanding());
    }

    @Test
    void theDaysLimitTripsOnItsOwn() {
        DefaultThreshold t = new DefaultThreshold(null, 45);
        assertFalse(DefaultDetector.evaluate(t, 0, TODAY, List.of(new OverdueItem(TODAY.minusDays(44), bd("100")))).breached());
        Verdict v = DefaultDetector.evaluate(t, 0, TODAY, List.of(new OverdueItem(TODAY.minusDays(45), bd("100"))));
        assertTrue(v.breached());
        assertTrue(v.reason().contains("45"));
    }

    @Test
    void eitherConditionIsEnough() {
        DefaultThreshold t = new DefaultThreshold(3, 30);
        assertTrue(DefaultDetector.evaluate(t, 0, TODAY, List.of(new OverdueItem(TODAY.minusDays(31), bd("100")))).breached());
    }

    @Test
    void noInstalmentsMeansNoBreach() {
        assertFalse(DefaultDetector.evaluate(DefaultThreshold.ofMissedInstalments(1), 0, TODAY, List.of()).breached());
    }

    // ---- AdvancePlanner ---------------------------------------------------------------------------------------------

    @Test
    void advanceIsUsedOldestFirstAndNeverMoreThanNeeded() {
        List<Use> uses = AdvancePlanner.consume(List.of(new Slice(1, 10, bd("300")), new Slice(2, 11, bd("5000"))), bd("1000"));
        assertEquals(2, uses.size());
        assertEquals(1L, uses.get(0).allocationId());
        eq("300.00", uses.get(0).amount());
        eq("700.00", uses.get(1).amount());
        eq("1000.00", AdvancePlanner.total(uses));
    }

    @Test
    void advanceThatRunsOutGivesWhatItHas() {
        List<Use> uses = AdvancePlanner.consume(List.of(new Slice(1, 10, bd("300")), new Slice(2, 11, bd("0"))), bd("1000"));
        assertEquals(1, uses.size());
        eq("300.00", AdvancePlanner.total(uses));
    }

    @Test
    void nothingIsUsedWhenNothingIsNeeded() {
        assertTrue(AdvancePlanner.consume(List.of(new Slice(1, 10, bd("300"))), bd("0")).isEmpty());
        assertTrue(AdvancePlanner.consume(List.of(), bd("500")).isEmpty());
    }

    // ---- ObligationLimit --------------------------------------------------------------------------------------------

    @Test
    void theLimitIsWhatIsRaisedPlusWhatIsToComeLessAdvance() {
        eq("45000.00", ObligationLimit.maxAcceptable(bd("5000"), 8, bd("5000"), bd("0")));
        eq("43000.00", ObligationLimit.maxAcceptable(bd("5000"), 8, bd("5000"), bd("2000")));
    }

    @Test
    void theLimitIsNeverNegative() {
        eq("0.00", ObligationLimit.maxAcceptable(bd("0"), 0, bd("5000"), bd("100")));
    }
}
