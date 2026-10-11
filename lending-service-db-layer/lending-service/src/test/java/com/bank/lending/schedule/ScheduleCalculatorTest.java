package com.bank.lending.schedule;

import com.bank.lending.account.domain.LoanAccount.DayCountConvention;
import com.bank.lending.account.domain.LoanAccount.RepaymentFrequency;
import com.bank.lending.account.domain.LoanAccount.ScheduleType;
import com.bank.lending.schedule.util.ScheduleCalculator;
import com.bank.lending.schedule.util.ScheduleCalculator.Installment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Spring-free: the instalment maths of every schedule type. */
class ScheduleCalculatorTest {

    private static final BigDecimal LAKH10 = new BigDecimal("1000000");
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 11, 5);

    private static ScheduleCalculator.Result run(ScheduleType type, DayCountConvention dc, RepaymentFrequency freq,
                                                  int installments, int moratorium, String balloon, String stepUp) {
        return ScheduleCalculator.generate(new ScheduleCalculator.Request(type, dc, freq, LAKH10, new BigDecimal("12"),
                installments, FIRST_DUE, moratorium, new BigDecimal(balloon), new BigDecimal(stepUp), 0));
    }

    private static void assertRepaysInFull(List<Installment> lines) {
        BigDecimal repaid = lines.stream().map(Installment::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(repaid).isEqualByComparingTo(LAKH10);
        assertThat(lines.get(lines.size() - 1).closingBalance()).isEqualByComparingTo("0");
        for (int i = 1; i < lines.size(); i++) {
            assertThat(lines.get(i).closingBalance())
                    .isEqualByComparingTo(lines.get(i - 1).closingBalance().subtract(lines.get(i).principal()));
        }
    }

    @Test
    void emiOn30360GivesLevelInstalments() {
        var r = run(ScheduleType.EMI, DayCountConvention.THIRTY_360, RepaymentFrequency.MONTHLY, 12, 0, "0", "0");

        assertThat(r.regularInstallment()).isEqualByComparingTo("88848.79");
        assertThat(r.installments().get(0).interest()).isEqualByComparingTo("10000.00");
        // last instalment only differs by rounding
        assertThat(r.installments().get(11).total()).isCloseTo(new BigDecimal("88848.79"), within(new BigDecimal("0.10")));
        assertRepaysInFull(r.installments());
    }

    @Test
    void actualDayCountsMakeInterestFollowTheDaysInEachMonth() {
        var r = run(ScheduleType.EMI, DayCountConvention.ACT_365, RepaymentFrequency.MONTHLY, 12, 0, "0", "0");

        // 5 Oct -> 5 Nov is 31 days; 5 Nov -> 5 Dec is 30 days
        assertThat(r.installments().get(0).interest()).isEqualByComparingTo("10191.78");
        assertRepaysInFull(r.installments());
    }

    @Test
    void act360ChargesMoreThanThe30360BasisButStillRepaysInFull() {
        var act360 = run(ScheduleType.EMI, DayCountConvention.ACT_360, RepaymentFrequency.MONTHLY, 60, 0, "0", "0");
        var thirty = run(ScheduleType.EMI, DayCountConvention.THIRTY_360, RepaymentFrequency.MONTHLY, 60, 0, "0", "0");

        assertThat(act360.regularInstallment()).isGreaterThan(thirty.regularInstallment());
        // the final instalment stays close to the others: the instalment was sized for the day count
        assertThat(act360.installments().get(59).total())
                .isCloseTo(act360.regularInstallment(), within(new BigDecimal("500")));
        assertRepaysInFull(act360.installments());
    }

    @Test
    void equalPrincipalRepaysTheSameAmountEachTimeSoInstalmentsFall() {
        var r = run(ScheduleType.EQUAL_PRINCIPAL, DayCountConvention.THIRTY_E_360, RepaymentFrequency.MONTHLY, 12, 0, "0", "0");
        var lines = r.installments();

        assertThat(lines.get(0).principal()).isEqualByComparingTo("83333.33");
        assertThat(lines.get(5).principal()).isEqualByComparingTo("83333.33");
        assertThat(lines.get(0).interest()).isEqualByComparingTo("10000.00");
        assertThat(r.regularInstallment()).isEqualByComparingTo("93333.33");
        for (int i = 1; i < lines.size(); i++) {
            assertThat(lines.get(i).total()).isLessThan(lines.get(i - 1).total());
        }
        assertRepaysInFull(lines);
    }

    @Test
    void interestOnlyPaysPrincipalWithTheLastInstalment() {
        var r = run(ScheduleType.INTEREST_ONLY, DayCountConvention.ACT_360, RepaymentFrequency.MONTHLY, 6, 0, "0", "0");
        var lines = r.installments();

        for (int i = 0; i < 5; i++) {
            assertThat(lines.get(i).principal()).isEqualByComparingTo("0");
            assertThat(lines.get(i).closingBalance()).isEqualByComparingTo(LAKH10);
        }
        assertThat(lines.get(0).interest()).isEqualByComparingTo("10333.33");   // 31 days / 360
        assertThat(lines.get(1).interest()).isEqualByComparingTo("10000.00");   // 30 days / 360
        assertThat(lines.get(5).principal()).isEqualByComparingTo(LAKH10);
        assertThat(r.regularInstallment()).isEqualByComparingTo("10333.33");
        assertRepaysInFull(lines);
    }

    @Test
    void balloonLeavesTheBalloonAmountForTheLastInstalment() {
        var r = run(ScheduleType.BALLOON, DayCountConvention.ACT_365, RepaymentFrequency.MONTHLY, 36, 0, "300000", "0");
        var lines = r.installments();
        var plain = run(ScheduleType.EMI, DayCountConvention.ACT_365, RepaymentFrequency.MONTHLY, 36, 0, "0", "0");

        assertThat(r.regularInstallment()).isLessThan(plain.regularInstallment());
        // the last instalment is a regular one plus the balloon (give or take day-count differences)
        assertThat(lines.get(35).total())
                .isCloseTo(r.regularInstallment().add(new BigDecimal("300000")), within(new BigDecimal("50.00")));
        assertThat(lines.get(34).total()).isCloseTo(r.regularInstallment(), within(new BigDecimal("1.00")));
        assertRepaysInFull(lines);
    }

    @Test
    void stepUpRaisesTheInstalmentEveryYear() {
        var r = run(ScheduleType.STEP_UP_EMI, DayCountConvention.THIRTY_360, RepaymentFrequency.MONTHLY, 36, 0, "0", "5");
        var lines = r.installments();

        assertThat(lines.get(0).total()).isEqualByComparingTo(lines.get(11).total());
        assertThat(lines.get(12).total()).isCloseTo(lines.get(0).total().multiply(new BigDecimal("1.05")), within(new BigDecimal("0.02")));
        assertThat(lines.get(24).total()).isCloseTo(lines.get(0).total().multiply(new BigDecimal("1.1025")), within(new BigDecimal("0.03")));
        assertThat(r.regularInstallment()).isEqualByComparingTo(lines.get(0).total());
        assertRepaysInFull(lines);
    }

    @Test
    void rescheduleContinuesTheStepUpYearItIsIn() {
        var original = run(ScheduleType.STEP_UP_EMI, DayCountConvention.THIRTY_360, RepaymentFrequency.MONTHLY, 36, 0, "0", "5");
        BigDecimal balanceAfterYearOne = original.installments().get(11).closingBalance();

        var rescheduled = ScheduleCalculator.generate(new ScheduleCalculator.Request(ScheduleType.STEP_UP_EMI,
                DayCountConvention.THIRTY_360, RepaymentFrequency.MONTHLY, balanceAfterYearOne, new BigDecimal("12"),
                24, FIRST_DUE.plusMonths(12), 0, BigDecimal.ZERO, new BigDecimal("5"), 12));

        assertThat(rescheduled.installments().get(0).total())
                .isCloseTo(original.installments().get(12).total(), within(new BigDecimal("0.10")));
        assertThat(rescheduled.installments().get(12).total())
                .isCloseTo(original.installments().get(24).total(), within(new BigDecimal("0.10")));
    }

    @Test
    void moratoriumPaysOnlyInterestFirst() {
        var r = run(ScheduleType.EMI, DayCountConvention.THIRTY_360, RepaymentFrequency.MONTHLY, 36, 12, "0", "0");
        var lines = r.installments();

        for (int i = 0; i < 12; i++) {
            assertThat(lines.get(i).principal()).isEqualByComparingTo("0");
            assertThat(lines.get(i).interest()).isEqualByComparingTo("10000.00");
        }
        assertThat(lines.get(12).principal()).isGreaterThan(BigDecimal.ZERO);
        // EMI over the remaining 24 months
        var twentyFour = run(ScheduleType.EMI, DayCountConvention.THIRTY_360, RepaymentFrequency.MONTHLY, 24, 0, "0", "0");
        assertThat(r.regularInstallment()).isEqualByComparingTo(twentyFour.regularInstallment());
        assertRepaysInFull(lines);
    }

    @Test
    void quarterlyRepaymentSpacesDueDatesThreeMonthsApartFromTheFirstDate() {
        var r = ScheduleCalculator.generate(new ScheduleCalculator.Request(ScheduleType.EMI,
                DayCountConvention.THIRTY_E_360, RepaymentFrequency.QUARTERLY, new BigDecimal("100000"),
                new BigDecimal("10"), 4, LocalDate.of(2026, 11, 30), 0, BigDecimal.ZERO, BigDecimal.ZERO, 0));
        var lines = r.installments();

        assertThat(lines.get(0).interest()).isEqualByComparingTo("2500.00");   // 3 months at 10% on 30E/360
        assertThat(lines.get(1).dueDate()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(lines.get(2).dueDate()).isEqualTo(LocalDate.of(2027, 5, 30));
        assertThat(lines.get(3).dueDate()).isEqualTo(LocalDate.of(2027, 8, 30));
        assertThat(lines.get(3).closingBalance()).isEqualByComparingTo("0");
    }

    @Test
    void zeroRateSplitsPrincipalEvenly() {
        var r = ScheduleCalculator.generate(new ScheduleCalculator.Request(ScheduleType.EMI, DayCountConvention.ACT_365,
                RepaymentFrequency.MONTHLY, new BigDecimal("1000"), BigDecimal.ZERO, 3, LocalDate.of(2026, 1, 31),
                0, BigDecimal.ZERO, BigDecimal.ZERO, 0));

        assertThat(r.installments().get(0).principal()).isEqualByComparingTo("333.33");
        assertThat(r.installments().get(2).principal()).isEqualByComparingTo("333.34");
        assertThat(r.installments().get(0).interest()).isEqualByComparingTo("0");
    }

    @Test
    void singleInstalmentClearsEverything() {
        var r = run(ScheduleType.EMI, DayCountConvention.THIRTY_360, RepaymentFrequency.YEARLY, 1, 0, "0", "0");

        assertThat(r.installments()).hasSize(1);
        assertThat(r.installments().get(0).principal()).isEqualByComparingTo(LAKH10);
        assertThat(r.installments().get(0).interest()).isEqualByComparingTo("120000.00");
    }
}
