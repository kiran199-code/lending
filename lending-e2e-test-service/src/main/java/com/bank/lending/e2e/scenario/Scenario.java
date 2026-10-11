package com.bank.lending.e2e.scenario;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** One row of the scenario matrix: what account to open and how the borrower will behave. */
public record Scenario(
        String scenarioId,
        Cohort cohort,
        int weight,
        String description,
        String productCode,
        BigDecimal amount,
        int tenureMonths,
        String repaymentFrequency,   // null = product default
        BigDecimal interestRate,     // null = product default
        String dayCount,             // null = product default (API token e.g. ACT_365)
        Integer moratoriumMonths,    // null = product default
        BigDecimal balloonPercent,
        BigDecimal stepUpPercent,
        Behaviour behaviour,
        Map<String, String> behaviourParams,
        int observeDaysAfterLastDue,
        List<String> requiresFeatures) {

    public enum Cohort { A, B }

    /** Borrower behaviours the driver knows how to play. All are parameterised from the CSV. */
    public enum Behaviour {
        /** Pays every instalment exactly on its due date. */
        ON_TIME,
        /** Pays every instalment {@code lateDays} after its due date. */
        LATE_PAY,
        /** On-time until {@code missInstalment}; pays that one {@code lateDays} late (then catches up) and resumes on time. */
        MISS_THEN_CURE,
        /** Pays the first {@code payInstalments} on time, then stops for good. Observed for observeDaysAfterLastDue. */
        STOP_PAYING,
        /** At {@code shortInstalment} first tries a short payment (expected rejected), pays correctly next day. */
        SHORT_PAY_THEN_PAY,
        /** After {@code afterInstalment} makes one part-payment of {@code percent} % of the outstanding principal, then on time. */
        PART_PAY_THEN_ON_TIME,
        /** On time until {@code atInstalment}; forecloses on that instalment's due date. */
        FORECLOSE_AT,
        /** Pays first {@code payInstalments} on time, stops, forecloses {@code forecloseAfterDays} after the first missed due date. */
        NPA_THEN_FORECLOSE,
        /** Pays first {@code payInstalments}, stops; probes restructuring support after NPA (expected NOT FOUND today). */
        RESTRUCTURE_AFTER_NPA
    }

    public String param(String key, String dflt) {
        return behaviourParams.getOrDefault(key, dflt);
    }

    public int intParam(String key, int dflt) {
        String v = behaviourParams.get(key);
        return v == null ? dflt : Integer.parseInt(v.trim());
    }
}
