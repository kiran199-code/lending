package com.bank.lending.schedule.util;

import com.bank.lending.account.domain.LoanAccount.RepaymentFrequency;
import com.bank.lending.account.domain.LoanAccount.ScheduleType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Which schedule parameters make sense together. Used for a product's defaults and for the values an
 * account is created with; both return a plain list of problems (empty = fine).
 */
public final class ScheduleParameterRules {

    private ScheduleParameterRules() {
    }

    /** Checks a product's default schedule settings and its maximums. */
    public static List<String> productProblems(ScheduleType type, RepaymentFrequency frequency,
                                               Collection<RepaymentFrequency> allowedFrequencies,
                                               Integer moratoriumMonths, Integer maxMoratoriumMonths,
                                               BigDecimal balloonPercent, BigDecimal maxBalloonPercent,
                                               BigDecimal stepUpPercent, BigDecimal maxStepUpPercent) {
        List<String> problems = new ArrayList<>();
        if (allowedFrequencies == null || allowedFrequencies.isEmpty()) {
            problems.add("allowedRepaymentFrequencies must list at least one frequency");
        } else if (!allowedFrequencies.contains(frequency)) {
            problems.add("repaymentFrequency " + frequency + " must be one of the allowed frequencies " + allowedFrequencies);
        }
        int moratorium = moratoriumMonths == null ? 0 : moratoriumMonths;
        problems.addAll(parameterProblems(type, frequency, null, moratorium,
                balloonPercent == null ? BigDecimal.ZERO : balloonPercent,
                stepUpPercent == null ? BigDecimal.ZERO : stepUpPercent));
        if (moratoriumMonths != null && moratoriumMonths < 0) {
            problems.add("moratoriumMonths must not be negative");
        }
        if (maxMoratoriumMonths != null) {
            if (maxMoratoriumMonths < moratorium) {
                problems.add("maxMoratoriumMonths must not be below moratoriumMonths");
            }
            if (type == ScheduleType.INTEREST_ONLY && maxMoratoriumMonths > 0) {
                problems.add("maxMoratoriumMonths must be 0 for an INTEREST_ONLY schedule");
            }
        }
        if (maxBalloonPercent != null) {
            if (maxBalloonPercent.signum() < 0 || maxBalloonPercent.compareTo(BigDecimal.valueOf(100)) >= 0) {
                problems.add("maxBalloonPercent must be at least 0 and below 100");
            } else if (balloonPercent != null && maxBalloonPercent.compareTo(balloonPercent) < 0) {
                problems.add("maxBalloonPercent must not be below balloonPercent");
            }
            if (type != ScheduleType.BALLOON && maxBalloonPercent.signum() > 0) {
                problems.add("maxBalloonPercent applies to BALLOON schedules only");
            }
        }
        if (maxStepUpPercent != null) {
            if (maxStepUpPercent.signum() < 0 || maxStepUpPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
                problems.add("maxStepUpPercent must be between 0 and 100");
            } else if (stepUpPercent != null && maxStepUpPercent.compareTo(stepUpPercent) < 0) {
                problems.add("maxStepUpPercent must not be below stepUpPercent");
            }
            if (type != ScheduleType.STEP_UP_EMI && maxStepUpPercent.signum() > 0) {
                problems.add("maxStepUpPercent applies to STEP_UP_EMI schedules only");
            }
        }
        return problems;
    }

    /**
     * Checks the settings an account is created with. {@code tenureMonths} may be null when it is
     * not known yet (product-level check).
     */
    public static List<String> parameterProblems(ScheduleType type, RepaymentFrequency frequency, Integer tenureMonths,
                                                 int moratoriumMonths, BigDecimal balloonPercent, BigDecimal stepUpPercent) {
        List<String> problems = new ArrayList<>();
        int step = frequency.months();

        if (tenureMonths != null && tenureMonths % step != 0) {
            problems.add("tenureMonths " + tenureMonths + " must be a multiple of " + step
                    + " for " + frequency + " repayment");
        }
        if (moratoriumMonths < 0) {
            problems.add("moratoriumMonths must not be negative");
        } else if (moratoriumMonths > 0) {
            if (type == ScheduleType.INTEREST_ONLY) {
                problems.add("moratoriumMonths must be 0 for an INTEREST_ONLY schedule (every instalment is interest only)");
            } else {
                if (moratoriumMonths % step != 0) {
                    problems.add("moratoriumMonths " + moratoriumMonths + " must be a multiple of " + step
                            + " for " + frequency + " repayment");
                }
                if (tenureMonths != null && moratoriumMonths + step > tenureMonths) {
                    problems.add("moratoriumMonths " + moratoriumMonths
                            + " leaves no instalment to repay principal within a tenure of " + tenureMonths + " months");
                }
            }
        }

        BigDecimal hundred = BigDecimal.valueOf(100);
        if (type == ScheduleType.BALLOON) {
            if (balloonPercent.signum() <= 0 || balloonPercent.compareTo(hundred) >= 0) {
                problems.add("balloonPercent must be above 0 and below 100 for a BALLOON schedule");
            }
        } else if (balloonPercent.signum() != 0) {
            problems.add("balloonPercent applies to BALLOON schedules only");
        }
        if (type == ScheduleType.STEP_UP_EMI) {
            if (stepUpPercent.signum() <= 0 || stepUpPercent.compareTo(hundred) > 0) {
                problems.add("stepUpPercent must be above 0 and at most 100 for a STEP_UP_EMI schedule");
            }
        } else if (stepUpPercent.signum() != 0) {
            problems.add("stepUpPercent applies to STEP_UP_EMI schedules only");
        }
        return problems;
    }
}
