package com.bank.lending.e2e.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bank.lending.e2e.scenario.Scenario.Behaviour;
import com.bank.lending.e2e.scenario.Scenario.Cohort;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioLoaderTest {

    private static final String HEADER = "scenarioId,cohort,weight,description,productCode,amount,tenureMonths,repaymentFrequency,interestRate,dayCount,"
            + "moratoriumMonths,balloonPercent,stepUpPercent,behaviour,behaviourParams,observeDaysAfterLastDue,requiresFeatures";

    @Test
    void bundledMatrixLoadsAndCoversBothCohorts() {
        List<Scenario> all = ScenarioLoader.load("classpath:scenarios.csv");
        assertTrue(all.stream().anyMatch(s -> s.cohort() == Cohort.A));
        assertTrue(all.stream().anyMatch(s -> s.cohort() == Cohort.B));
        // every stress behaviour of the brief (a..g) is present
        for (Behaviour b : List.of(Behaviour.STOP_PAYING, Behaviour.MISS_THEN_CURE, Behaviour.SHORT_PAY_THEN_PAY, Behaviour.NPA_THEN_FORECLOSE,
                Behaviour.RESTRUCTURE_AFTER_NPA)) {
            assertTrue(all.stream().anyMatch(s -> s.cohort() == Cohort.B && s.behaviour() == b), "missing " + b);
        }
    }

    @Test
    void bundledMatrixCoversEveryScheduleTypeAndProduct() {
        List<Scenario> all = ScenarioLoader.load("classpath:scenarios.csv");
        for (String p : List.of("PL01", "TL01", "BB01", "AB01", "SU01", "EM01", "AL01")) {
            assertTrue(all.stream().anyMatch(s -> s.productCode().equals(p)), "no scenario for " + p);
        }
    }

    @Test
    void expandHonoursCountAndCohortSplit() {
        List<Scenario> out = ScenarioLoader.expand(ScenarioLoader.load("classpath:scenarios.csv"), 20, 50);
        assertEquals(20, out.size());
        assertEquals(10, out.stream().filter(s -> s.cohort() == Cohort.A).count());
        assertEquals(10, out.stream().filter(s -> s.cohort() == Cohort.B).count());
        assertFalse(out.stream().anyMatch(s -> s.scenarioId().equals("B09")), "weight 0 rows are disabled");
    }

    @Test
    void accountCountIsConfigurable() {
        assertEquals(7, ScenarioLoader.expand(ScenarioLoader.load("classpath:scenarios.csv"), 7, 50).size());
        assertEquals(100, ScenarioLoader.expand(ScenarioLoader.load("classpath:scenarios.csv"), 100, 50).size());
    }

    @Test
    void parsesParamsAndBlanksAsNull() {
        List<Scenario> l = ScenarioLoader.parse(List.of(HEADER,
                "X1,B,2,\"a, with comma\",PL01,100000,12,,,,,,,MISS_THEN_CURE,missInstalment=3;lateDays=20,30,ACCRUAL|DPD"));
        Scenario s = l.get(0);
        assertEquals("a, with comma", s.description());
        assertEquals(3, s.intParam("missInstalment", 0));
        assertEquals(20, s.intParam("lateDays", 0));
        assertEquals(null, s.interestRate());
        assertEquals(List.of("ACCRUAL", "DPD"), s.requiresFeatures());
    }

    @Test
    void duplicateIdsAndMissingColumnsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ScenarioLoader.parse(List.of(HEADER,
                "X1,A,1,d,PL01,100000,12,,,,,,,ON_TIME,,0,", "X1,A,1,d,PL01,100000,12,,,,,,,ON_TIME,,0,")));
        assertThrows(IllegalArgumentException.class, () -> ScenarioLoader.parse(List.of(HEADER, "X1,A,1,d,,100000,12,,,,,,,ON_TIME,,0,")));
    }
}
