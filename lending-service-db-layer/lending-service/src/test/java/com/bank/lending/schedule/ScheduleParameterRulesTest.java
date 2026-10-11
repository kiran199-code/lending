package com.bank.lending.schedule;

import com.bank.lending.account.domain.LoanAccount.RepaymentFrequency;
import com.bank.lending.account.domain.LoanAccount.ScheduleType;
import com.bank.lending.schedule.util.ScheduleParameterRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring-free: which schedule parameters fit which schedule type. */
class ScheduleParameterRulesTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static List<String> account(ScheduleType type, RepaymentFrequency f, Integer tenure, int moratorium,
                                         String balloon, String stepUp) {
        return ScheduleParameterRules.parameterProblems(type, f, tenure, moratorium, new BigDecimal(balloon), new BigDecimal(stepUp));
    }

    @Test
    void plainEmiWithNoExtrasIsFine() {
        assertThat(account(ScheduleType.EMI, RepaymentFrequency.MONTHLY, 36, 0, "0", "0")).isEmpty();
    }

    @Test
    void tenureMustBeWholePeriods() {
        assertThat(account(ScheduleType.EMI, RepaymentFrequency.QUARTERLY, 38, 0, "0", "0"))
                .anyMatch(p -> p.contains("tenureMonths 38 must be a multiple of 3"));
    }

    @Test
    void moratoriumMustFitFrequencyAndLeaveRoomToRepay() {
        assertThat(account(ScheduleType.EMI, RepaymentFrequency.QUARTERLY, 36, 5, "0", "0"))
                .anyMatch(p -> p.contains("moratoriumMonths 5 must be a multiple of 3"));
        assertThat(account(ScheduleType.EMI, RepaymentFrequency.MONTHLY, 12, 12, "0", "0"))
                .anyMatch(p -> p.contains("leaves no instalment"));
        assertThat(account(ScheduleType.EMI, RepaymentFrequency.MONTHLY, 36, 12, "0", "0")).isEmpty();
    }

    @Test
    void interestOnlyCannotHaveAMoratorium() {
        assertThat(account(ScheduleType.INTEREST_ONLY, RepaymentFrequency.MONTHLY, 12, 3, "0", "0"))
                .anyMatch(p -> p.contains("must be 0 for an INTEREST_ONLY"));
    }

    @Test
    void balloonPercentIsRequiredForBalloonAndForbiddenElsewhere() {
        assertThat(account(ScheduleType.BALLOON, RepaymentFrequency.MONTHLY, 36, 0, "0", "0"))
                .anyMatch(p -> p.contains("balloonPercent must be above 0"));
        assertThat(account(ScheduleType.BALLOON, RepaymentFrequency.MONTHLY, 36, 0, "30", "0")).isEmpty();
        assertThat(account(ScheduleType.EMI, RepaymentFrequency.MONTHLY, 36, 0, "30", "0"))
                .anyMatch(p -> p.contains("balloonPercent applies to BALLOON"));
    }

    @Test
    void stepUpPercentIsRequiredForStepUpAndForbiddenElsewhere() {
        assertThat(account(ScheduleType.STEP_UP_EMI, RepaymentFrequency.MONTHLY, 36, 0, "0", "0"))
                .anyMatch(p -> p.contains("stepUpPercent must be above 0"));
        assertThat(account(ScheduleType.STEP_UP_EMI, RepaymentFrequency.MONTHLY, 36, 0, "0", "5")).isEmpty();
        assertThat(account(ScheduleType.EQUAL_PRINCIPAL, RepaymentFrequency.MONTHLY, 36, 0, "0", "5"))
                .anyMatch(p -> p.contains("stepUpPercent applies to STEP_UP_EMI"));
    }

    @Test
    void productDefaultFrequencyMustBeAllowed() {
        List<String> problems = ScheduleParameterRules.productProblems(ScheduleType.EMI, RepaymentFrequency.MONTHLY,
                List.of(RepaymentFrequency.QUARTERLY), 0, null, ZERO, null, ZERO, null);
        assertThat(problems).anyMatch(p -> p.contains("must be one of the allowed frequencies"));
    }

    @Test
    void productMaximumsMustMatchTheScheduleType() {
        assertThat(ScheduleParameterRules.productProblems(ScheduleType.EMI, RepaymentFrequency.MONTHLY,
                List.of(RepaymentFrequency.MONTHLY), 0, null, ZERO, new BigDecimal("20"), ZERO, new BigDecimal("5")))
                .anyMatch(p -> p.contains("maxBalloonPercent applies to BALLOON"))
                .anyMatch(p -> p.contains("maxStepUpPercent applies to STEP_UP_EMI"));
        assertThat(ScheduleParameterRules.productProblems(ScheduleType.BALLOON, RepaymentFrequency.MONTHLY,
                List.of(RepaymentFrequency.MONTHLY), 0, null, new BigDecimal("30"), new BigDecimal("20"), ZERO, null))
                .anyMatch(p -> p.contains("maxBalloonPercent must not be below balloonPercent"));
        assertThat(ScheduleParameterRules.productProblems(ScheduleType.INTEREST_ONLY, RepaymentFrequency.MONTHLY,
                List.of(RepaymentFrequency.MONTHLY), 0, 6, ZERO, null, ZERO, null))
                .anyMatch(p -> p.contains("maxMoratoriumMonths must be 0 for an INTEREST_ONLY"));
    }

    @Test
    void validProductSettingsPass() {
        assertThat(ScheduleParameterRules.productProblems(ScheduleType.BALLOON, RepaymentFrequency.MONTHLY,
                List.of(RepaymentFrequency.MONTHLY, RepaymentFrequency.QUARTERLY), 0, 6,
                new BigDecimal("30"), new BigDecimal("50"), ZERO, null)).isEmpty();
    }
}
