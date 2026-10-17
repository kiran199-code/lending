package com.bank.lending.demand;

import com.bank.lending.account.domain.LoanAccount.DayCountConvention;
import com.bank.lending.demand.util.PenalInterestCalculator;
import com.bank.lending.demand.util.PenalInterestCalculator.Accrual;
import com.bank.lending.demand.util.PenalInterestCalculator.Paid;
import com.bank.lending.demand.util.PenalInterestCalculator.Settings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure Java: the worked examples of the penal interest requirement (24% a year, ACT/365, 0 grace days, no cap). */
class PenalInterestCalculatorTest {

    private static final LocalDate DUE = LocalDate.of(2026, 11, 5);
    private static final Settings STANDARD = new Settings(true, new BigDecimal("24"), 0, null);

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static LocalDate nov(int day) {
        return LocalDate.of(2026, 11, day);
    }

    private static List<Accrual> accruals(List<Paid> paid, String alreadyCharged, LocalDate lastCharged,
                                          LocalDate through, Settings settings) {
        return PenalInterestCalculator.accruals(DUE, n("15000.00"), paid, n(alreadyCharged), lastCharged, through,
                settings, DayCountConvention.ACT_365);
    }

    @Test
    void caseA_eightUnpaidDaysCharge78_90() {
        List<Accrual> days = accruals(List.of(), "0", null, nov(13), STANDARD);

        assertThat(days).hasSize(8);
        assertThat(days.get(0).date()).isEqualTo(nov(6));
        assertThat(days.get(0).amount()).isEqualByComparingTo("9.8630");
        assertThat(PenalInterestCalculator.total(days)).isEqualByComparingTo("78.9040");
    }

    @Test
    void aDemandPaidInFullOnADateIsNotChargedForThatDate() {
        List<Accrual> days = accruals(List.of(new Paid(nov(14), n("15000.00"))), "0", null, nov(14), STANDARD);

        assertThat(days).hasSize(8);
        assertThat(days.get(days.size() - 1).date()).isEqualTo(nov(13));
    }

    @Test
    void caseB_aPartPaymentShrinksTheBase() {
        // 10,000 paid on 14 Nov: 78.90 penal, 3,000 interest, 6,921.10 principal, so 9,921.10 of the demand is cleared
        List<Accrual> days = accruals(List.of(new Paid(nov(14), n("9921.10"))), "78.9040", nov(13), nov(19), STANDARD);

        assertThat(days).hasSize(6);
        assertThat(days.get(0).base()).isEqualByComparingTo("5078.90");
        assertThat(days.get(0).amount()).isEqualByComparingTo("3.3396");
        assertThat(PenalInterestCalculator.total(days).setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("20.04");
    }

    @Test
    void caseC_eachDemandCountsItsOwnDays() {
        List<Accrual> first = accruals(List.of(), "0", null, LocalDate.of(2026, 12, 9), STANDARD);
        List<Accrual> second = PenalInterestCalculator.accruals(LocalDate.of(2026, 12, 5), n("15000.00"), List.of(),
                BigDecimal.ZERO, null, LocalDate.of(2026, 12, 9), STANDARD, DayCountConvention.ACT_365);

        assertThat(first).hasSize(34);
        assertThat(PenalInterestCalculator.total(first).setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("335.34");
        assertThat(second).hasSize(4);
        assertThat(PenalInterestCalculator.total(second).setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("39.45");
    }

    @Test
    void catchingUpInTwoStepsGivesTheSameTotalAsOneRun() {
        List<Accrual> all = new ArrayList<>(accruals(List.of(), "0", null, nov(10), STANDARD));
        all.addAll(accruals(List.of(), PenalInterestCalculator.total(all).toPlainString(), nov(10), nov(13), STANDARD));

        assertThat(all).hasSize(8);
        assertThat(PenalInterestCalculator.total(all)).isEqualByComparingTo("78.9040");
    }

    @Test
    void runningTwiceForTheSameDatesChargesNothingNew() {
        List<Accrual> again = accruals(List.of(), "78.9040", nov(13), nov(13), STANDARD);

        assertThat(again).isEmpty();
    }

    @Test
    void graceDaysDelayTheFirstChargedDay() {
        List<Accrual> days = accruals(List.of(), "0", null, nov(12), new Settings(true, new BigDecimal("24"), 5, null));

        assertThat(days).extracting(Accrual::date).containsExactly(nov(11), nov(12));
    }

    @Test
    void aCapStopsTheChargeAtAPercentageOfTheDemand() {
        List<Accrual> days = accruals(List.of(), "0", null, LocalDate.of(2027, 6, 5),
                new Settings(true, new BigDecimal("24"), 0, new BigDecimal("10")));

        assertThat(PenalInterestCalculator.total(days)).isEqualByComparingTo("1500.0000");
    }

    @Test
    void aSwitchedOffProductOrAZeroRateChargesNothing() {
        assertThat(accruals(List.of(), "0", null, nov(30), Settings.OFF)).isEmpty();
        assertThat(accruals(List.of(), "0", null, nov(30), new Settings(true, BigDecimal.ZERO, 0, null))).isEmpty();
    }

    @Test
    void theDayCountConventionSetsTheDaysInTheYear() {
        List<Accrual> act360 = PenalInterestCalculator.accruals(DUE, n("36000.00"), List.of(), BigDecimal.ZERO, null,
                nov(6), STANDARD, DayCountConvention.ACT_360);

        assertThat(act360.get(0).amount()).isEqualByComparingTo("24.0000"); // 36,000 x 24% / 360
    }
}
