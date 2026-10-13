package com.bank.lending.maintenance.service;

import com.bank.lending.account.domain.LoanAccount.AccountType;
import com.bank.lending.account.domain.LoanAccount.DayCountConvention;
import com.bank.lending.account.domain.LoanAccount.RateType;
import com.bank.lending.account.domain.LoanAccount.RepaymentFrequency;
import com.bank.lending.account.domain.LoanAccount.ScheduleType;
import com.bank.lending.product.dto.LoanProductRequest;

import java.math.BigDecimal;
import java.util.List;

/**
 * The base product set: the six standard products that migration V10 seeds (personal, business,
 * education, gold, auto and home loans) plus the five sample schedule-type products that V11 seeds
 * (equal-principal, interest-only, balloon, step-up and study-moratorium). Creating them again after
 * a clear gives the same starting point as a fresh database, and the products the end-to-end test
 * suite's scenarios rely on (TL01, BB01, AB01, SU01, EM01).
 */
public final class BaseProducts {

    private BaseProducts() {
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static LoanProductRequest fixed(String code, String name, String description, String rate,
                                            String minRate, String maxRate, String minAmount, String maxAmount,
                                            int minTenure, int maxTenure) {
        return new LoanProductRequest(code, name, description, AccountType.UNSECURED, RateType.FIXED,
                null, DayCountConvention.ACT_365, null, d(rate), null,
                d(minRate), d(maxRate), null, null, d(minAmount), d(maxAmount), minTenure, maxTenure, null, null);
    }

    private static LoanProductRequest floating(String code, String name, String description, AccountType type,
                                               String rate, String spread, String minAmount, String maxAmount,
                                               int minTenure, int maxTenure) {
        return new LoanProductRequest(code, name, description, type, RateType.FLOATING,
                null, DayCountConvention.ACT_365, "REPO", d(rate), d(spread),
                d("8.0000"), d("13.0000"), d("1.0000"), d("5.0000"), d(minAmount), d(maxAmount),
                minTenure, maxTenure, null, null);
    }

    /** A fixed-rate UNSECURED sample product with its own schedule type, day count and frequencies. */
    private static LoanProductRequest sample(String code, String name, String description, ScheduleType schedule,
                                             DayCountConvention dayCount, String rate, String minRate, String maxRate,
                                             String minAmount, String maxAmount, int minTenure, int maxTenure,
                                             List<RepaymentFrequency> frequencies, Integer moratorium,
                                             Integer maxMoratorium, String balloon, String maxBalloon,
                                             String stepUp, String maxStepUp) {
        return new LoanProductRequest(code, name, description, AccountType.UNSECURED, RateType.FIXED,
                schedule, dayCount, null, d(rate), null,
                d(minRate), d(maxRate), null, null, d(minAmount), d(maxAmount), minTenure, maxTenure,
                RepaymentFrequency.MONTHLY, frequencies, moratorium, maxMoratorium,
                balloon == null ? null : d(balloon), maxBalloon == null ? null : d(maxBalloon),
                stepUp == null ? null : d(stepUp), maxStepUp == null ? null : d(maxStepUp), null, null);
    }

    public static List<LoanProductRequest> all() {
        return List.of(
                fixed("PL01", "Personal Loan", "Unsecured fixed-rate personal loan",
                        "10.5000", "8.0000", "17.0000", "50000", "5000000", 6, 84),
                fixed("BL01", "Business Loan", "Unsecured fixed-rate business loan",
                        "11.5000", "9.0000", "17.0000", "100000", "10000000", 12, 120),
                fixed("EL01", "Education Loan", "Unsecured fixed-rate education loan",
                        "9.5000", "8.0000", "13.0000", "100000", "5000000", 12, 120),
                fixed("GL01", "Gold Loan", "Short-tenure fixed-rate loan against gold",
                        "9.0000", "7.0000", "13.0000", "10000", "1000000", 3, 36),
                floating("AL01", "Auto Loan", "Unsecured floating-rate vehicle loan linked to REPO",
                        AccountType.UNSECURED, "9.7500", "3.0000", "100000", "5000000", 12, 84),
                floating("HL01", "Home Loan", "Floating-rate home loan secured by property, linked to REPO",
                        AccountType.MORTGAGE, "8.7500", "2.5000", "500000", "50000000", 60, 360),
                sample("TL01", "Term Loan - Equal Principal",
                        "Fixed principal every instalment, so the instalment falls over time; interest on 30E/360",
                        ScheduleType.EQUAL_PRINCIPAL, DayCountConvention.THIRTY_E_360,
                        "11.0000", "9.0000", "16.0000", "100000", "20000000", 12, 120,
                        List.of(RepaymentFrequency.MONTHLY, RepaymentFrequency.QUARTERLY, RepaymentFrequency.HALF_YEARLY),
                        0, 12, null, null, null, null),
                sample("BB01", "Bridge Loan - Interest Only",
                        "Interest-only instalments with the whole principal repaid in the last one (bullet)",
                        ScheduleType.INTEREST_ONLY, DayCountConvention.ACT_360,
                        "12.0000", "10.0000", "18.0000", "500000", "50000000", 3, 36,
                        List.of(RepaymentFrequency.MONTHLY, RepaymentFrequency.QUARTERLY),
                        0, 0, null, null, null, null),
                sample("AB01", "Auto Loan - Balloon",
                        "Lower instalments with a balloon share of the amount due with the last instalment",
                        ScheduleType.BALLOON, DayCountConvention.ACT_365,
                        "9.5000", "8.0000", "13.0000", "100000", "5000000", 12, 84,
                        List.of(RepaymentFrequency.MONTHLY),
                        0, 6, "30.00", "50.00", null, null),
                sample("SU01", "Step-up Personal Loan",
                        "Instalment grows by a set percentage each year; for borrowers whose income is expected to rise",
                        ScheduleType.STEP_UP_EMI, DayCountConvention.ACT_365,
                        "12.0000", "9.0000", "17.0000", "50000", "3000000", 12, 84,
                        List.of(RepaymentFrequency.MONTHLY, RepaymentFrequency.QUARTERLY),
                        0, 6, null, null, "5.00", "15.00"),
                sample("EM01", "Education Loan - Study Moratorium",
                        "Interest-only during the study period, then equated instalments",
                        ScheduleType.EMI, DayCountConvention.THIRTY_360,
                        "9.5000", "8.0000", "13.0000", "100000", "5000000", 24, 144,
                        List.of(RepaymentFrequency.MONTHLY, RepaymentFrequency.QUARTERLY),
                        12, 48, null, null, null, null));
    }
}
