package com.bank.lending.chit.calc;

import com.bank.lending.chit.calc.AllocationEngine.DemandDue;
import com.bank.lending.chit.calc.AllocationEngine.Line;
import com.bank.lending.chit.calc.AllocationEngine.Result;
import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.domain.AllocationBucket;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocationEngineTest {

    private static final List<AllocationBucket> DEFAULT_ORDER =
            List.of(AllocationBucket.LATE_FEES, AllocationBucket.ARREARS, AllocationBucket.CURRENT);

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** Cycle 1 and 2 are overdue (one fee each), cycle 3 is current. */
    private static List<DemandDue> dues() {
        return List.of(
                new DemandDue(103, 3, bd("5000.00"), bd("0.00")),
                new DemandDue(101, 1, bd("5000.00"), bd("100.00")),
                new DemandDue(102, 2, bd("4250.00"), bd("50.00")));
    }

    private static BigDecimal total(Result r) {
        return r.lines().stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void defaultOrderIsLateFeesThenOldestArrearsThenCurrent() {
        Result r = AllocationEngine.allocate(bd("14400.00"), 3, dues(), DEFAULT_ORDER);
        assertEquals(5, r.lines().size());
        assertEquals(AllocationBucket.LATE_FEES, r.lines().get(0).bucket());
        assertEquals(101L, r.lines().get(0).demandId());          // oldest fee first
        assertEquals(bd("100.00"), r.lines().get(0).amount());
        assertEquals(bd("50.00"), r.lines().get(1).amount());     // then the next fee
        assertEquals(AllocationBucket.ARREARS, r.lines().get(2).bucket());
        assertEquals(101L, r.lines().get(2).demandId());          // oldest arrears first
        assertEquals(AllocationBucket.ARREARS, r.lines().get(3).bucket());
        assertEquals(102L, r.lines().get(3).demandId());
        assertEquals(AllocationBucket.CURRENT, r.lines().get(4).bucket());
        assertEquals(bd("0.00"), r.advance());
        assertEquals(bd("14400.00"), r.allocated());
    }

    @Test
    void aPartialPaymentRunsOutPartWayDown() {
        // 5,120.00: fees 150.00, then 4,970.00 of the oldest arrears (cycle 1, 5,000.00 owed)
        Result r = AllocationEngine.allocate(bd("5120.00"), 3, dues(), DEFAULT_ORDER);
        assertEquals(3, r.lines().size());
        assertEquals(AllocationBucket.ARREARS, r.lines().get(2).bucket());
        assertEquals(bd("4970.00"), r.lines().get(2).amount());
        assertEquals(bd("5120.00"), total(r));
        assertEquals(bd("0.00"), r.advance());
    }

    @Test
    void moneyBeyondWhatIsOwedBecomesAnAdvance() {
        Result r = AllocationEngine.allocate(bd("20000.00"), 3, dues(), DEFAULT_ORDER);
        Line last = r.lines().get(r.lines().size() - 1);
        assertEquals(AllocationBucket.ADVANCE, last.bucket());
        assertNull(last.demandId());
        assertNull(last.cycleNo());
        // owed in total: fees 150 + 5,000 + 4,250 + 5,000 = 14,400.00
        assertEquals(bd("14400.00"), r.allocated());
        assertEquals(bd("5600.00"), r.advance());
        assertEquals(bd("20000.00"), total(r));
    }

    @Test
    void aCustomOrderPaysTheCurrentInstalmentFirst() {
        List<AllocationBucket> currentFirst = List.of(AllocationBucket.CURRENT, AllocationBucket.LATE_FEES, AllocationBucket.ARREARS);
        Result r = AllocationEngine.allocate(bd("5000.00"), 3, dues(), currentFirst);
        assertEquals(1, r.lines().size());
        assertEquals(AllocationBucket.CURRENT, r.lines().get(0).bucket());
        assertEquals(103L, r.lines().get(0).demandId());
    }

    @Test
    void nothingOwedMeansTheWholePaymentIsAnAdvance() {
        Result r = AllocationEngine.allocate(bd("100.00"), 1, List.of(), DEFAULT_ORDER);
        assertEquals(1, r.lines().size());
        assertEquals(AllocationBucket.ADVANCE, r.lines().get(0).bucket());
        assertEquals(bd("0.00"), r.allocated());
    }

    @Test
    void refusesABadPaymentOrOrder() {
        assertEquals("PAYMENT_NOT_POSITIVE", assertThrows(ChitException.class,
                () -> AllocationEngine.allocate(bd("0.00"), 1, dues(), DEFAULT_ORDER)).code());
        assertEquals("ALLOCATION_ORDER_INVALID", assertThrows(ChitException.class, () -> AllocationEngine.allocate(bd("10.00"), 1, dues(),
                List.of(AllocationBucket.LATE_FEES, AllocationBucket.CURRENT, AllocationBucket.CURRENT))).code());
    }

    @Test
    void propertyEveryRupeeOfThePaymentIsPlacedOnceAndNothingIsOverpaid() {
        Random random = new Random(99L);
        for (int run = 0; run < 1000; run++) {
            int current = 5;
            java.util.ArrayList<DemandDue> list = new java.util.ArrayList<>();
            for (int c = 1; c <= current; c++) {
                list.add(new DemandDue(c, c, BigDecimal.valueOf(random.nextInt(500000), 2), BigDecimal.valueOf(random.nextInt(20000), 2)));
            }
            BigDecimal payment = BigDecimal.valueOf(1 + random.nextInt(3_000_000), 2);
            Result r = AllocationEngine.allocate(payment, current, list, DEFAULT_ORDER);
            assertEquals(payment, total(r), "run " + run);
            assertEquals(payment, r.allocated().add(r.advance()), "run " + run);
            for (DemandDue d : list) {
                BigDecimal fee = r.lines().stream().filter(l -> l.bucket() == AllocationBucket.LATE_FEES && l.demandId() != null && l.demandId() == d.demandId())
                        .map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal sub = r.lines().stream().filter(l -> (l.bucket() == AllocationBucket.ARREARS || l.bucket() == AllocationBucket.CURRENT) && l.demandId() != null && l.demandId() == d.demandId())
                        .map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                assertTrue(fee.compareTo(d.lateFeeOutstanding()) <= 0, "fee overpaid, run " + run);
                assertTrue(sub.compareTo(d.subscriptionOutstanding()) <= 0, "subscription overpaid, run " + run);
            }
        }
    }
}
