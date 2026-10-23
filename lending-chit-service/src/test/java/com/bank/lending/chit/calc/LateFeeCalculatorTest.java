package com.bank.lending.chit.calc;

import com.bank.lending.chit.calc.LateFeeCalculator.DailyAccrual;
import com.bank.lending.chit.domain.rules.LateFeeRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LateFeeCalculatorTest {

    private static final LocalDate DUE = LocalDate.of(2026, 11, 5);
    private static final BigDecimal OVERDUE = new BigDecimal("5000.00");

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void noRuleNoFee() {
        assertEquals(bd("0.00"), LateFeeCalculator.accrualForDay(LateFeeRule.none(), OVERDUE, DUE, DUE.plusDays(30), 0, null));
    }

    @Test
    void nothingIsChargedWithinGrace() {
        LateFeeRule rule = LateFeeRule.flat(bd("100"));
        assertEquals(bd("0.00"), LateFeeCalculator.accrualForDay(rule, OVERDUE, DUE, DUE, 3, null));
        assertEquals(bd("0.00"), LateFeeCalculator.accrualForDay(rule, OVERDUE, DUE, DUE.plusDays(3), 3, null));
        assertEquals(bd("100.00"), LateFeeCalculator.accrualForDay(rule, OVERDUE, DUE, DUE.plusDays(4), 3, null));
    }

    @Test
    void flatFeeIsChargedOnceOnly() {
        LateFeeRule rule = LateFeeRule.flat(bd("100"));
        List<DailyAccrual> p = LateFeeCalculator.preview(rule, OVERDUE, DUE, DUE, 10, 2, null);
        assertEquals(bd("100.00"), p.get(9).cumulative());
        long charged = p.stream().filter(a -> a.accrual().signum() > 0).count();
        assertEquals(1L, charged);
    }

    @Test
    void flatFeeIsNotChargedAgainOnARerunOrCatchUp() {
        LateFeeRule rule = LateFeeRule.flat(bd("100"));
        // the first chargeable day was missed; a later run charges it once, a further run charges nothing
        assertEquals(bd("100.00"), LateFeeCalculator.accrualForDay(rule, OVERDUE, DUE, DUE.plusDays(6), 2, bd("0.00")));
        assertEquals(bd("0.00"), LateFeeCalculator.accrualForDay(rule, OVERDUE, DUE, DUE.plusDays(7), 2, bd("100.00")));
    }

    @Test
    void percentPerDayAccruesDailyAndStopsAtTheAmountCap() {
        // 0.1% of 5,000.00 = 5.00 a day; cap 20.00
        LateFeeRule rule = LateFeeRule.percentPerDay(bd("0.1"), bd("20"), null);
        List<DailyAccrual> p = LateFeeCalculator.preview(rule, OVERDUE, DUE, DUE.plusDays(1), 8, 0, null);
        assertEquals(bd("5.00"), p.get(0).accrual());
        assertEquals(bd("20.00"), p.get(3).cumulative());
        assertEquals(bd("0.00"), p.get(4).accrual());
        assertEquals(bd("20.00"), p.get(7).cumulative());
    }

    @Test
    void thePercentageCapAndTheAmountCapTakeTheLower() {
        // cap 2% of 5,000.00 = 100.00 vs cap amount 50.00 -> 50.00
        LateFeeRule rule = LateFeeRule.percentPerDay(bd("1"), bd("50"), bd("2"));
        List<DailyAccrual> p = LateFeeCalculator.preview(rule, OVERDUE, DUE, DUE.plusDays(1), 5, 0, null);
        assertEquals(bd("50.00"), p.get(4).cumulative());
        // only the percentage cap
        LateFeeRule pctOnly = LateFeeRule.percentPerDay(bd("1"), null, bd("2"));
        assertEquals(bd("100.00"), LateFeeCalculator.preview(pctOnly, OVERDUE, DUE, DUE.plusDays(1), 5, 0, null).get(4).cumulative());
    }

    @Test
    void aPartPaymentLowersTheFeeFromThenOn() {
        LateFeeRule rule = LateFeeRule.percentPerDay(bd("0.1"), bd("1000"), null);
        BigDecimal soFar = LateFeeCalculator.accrualForDay(rule, bd("5000.00"), DUE, DUE.plusDays(1), 0, null); // 5.00
        BigDecimal next = LateFeeCalculator.accrualForDay(rule, bd("2000.00"), DUE, DUE.plusDays(2), 0, soFar);   // 2.00
        assertEquals(bd("5.00"), soFar);
        assertEquals(bd("2.00"), next);
    }

    @Test
    void nothingOverdueNothingCharged() {
        LateFeeRule rule = LateFeeRule.percentPerDay(bd("0.1"), bd("1000"), null);
        assertEquals(bd("0.00"), LateFeeCalculator.accrualForDay(rule, bd("0.00"), DUE, DUE.plusDays(5), 0, null));
    }

    @Test
    void previewBeforeTheDueDateIsEmptyOfFees() {
        LateFeeRule rule = LateFeeRule.flat(bd("100"));
        List<DailyAccrual> p = LateFeeCalculator.preview(rule, OVERDUE, DUE, DUE.minusDays(3), 3, 0, null);
        assertEquals(bd("0.00"), p.get(2).cumulative());
    }
}
