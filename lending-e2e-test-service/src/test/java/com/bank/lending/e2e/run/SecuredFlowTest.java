package com.bank.lending.e2e.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bank.lending.e2e.config.E2eConfig;
import com.bank.lending.e2e.config.EnvironmentGuard;
import com.bank.lending.e2e.scenario.Scenario;
import com.bank.lending.e2e.scenario.ScenarioLoader;
import com.bank.lending.e2e.teardown.Teardown;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The secured flow is separate from the unsecured one: own scenario file, own products, own settings, own clean-up. */
class SecuredFlowTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode products() throws Exception {
        return M.readTree("""
                [{"productCode":"PL01","status":"ACTIVE","accountType":"UNSECURED","scheduleType":"EMI","rateType":"FIXED",
                  "minSanctionedAmount":50000,"maxSanctionedAmount":5000000,"minTenureMonths":6,"maxTenureMonths":84,
                  "repaymentFrequency":"MONTHLY","allowedRepaymentFrequencies":["MONTHLY"]},
                 {"productCode":"HL01","status":"ACTIVE","accountType":"MORTGAGE","scheduleType":"EMI","rateType":"FIXED",
                  "minSanctionedAmount":500000,"maxSanctionedAmount":50000000,"minTenureMonths":60,"maxTenureMonths":360,
                  "repaymentFrequency":"MONTHLY","allowedRepaymentFrequencies":["MONTHLY"]}]""");
    }

    private static Scenario sc(String id, String product) {
        return new Scenario(id, Scenario.Cohort.A, 1, id, product, new BigDecimal("2500000"), 120, null, null, null, null, null, null,
                Scenario.Behaviour.ON_TIME, Map.of("horizonDays", "95"), 0, List.of(SecuredFlow.FEATURE));
    }

    @Test
    void securedMatrixLoadsAndEveryRowIsMarkedSecuredOnAMortgageProduct() {
        List<Scenario> rows = ScenarioLoader.load("classpath:secured-scenarios.csv");
        assertFalse(rows.isEmpty());
        for (Scenario s : rows) {
            assertTrue(SecuredFlow.isSecured(s), s.scenarioId() + " must carry COLLATERAL");
            assertEquals("HL01", s.productCode());
            assertTrue(s.intParam("horizonDays", 0) > 0 || s.behaviour() == Scenario.Behaviour.FORECLOSE_AT, s.scenarioId());
        }
    }

    @Test
    void theUnsecuredMatrixHasNoSecuredRows() {
        for (Scenario s : ScenarioLoader.load("classpath:scenarios.csv")) {
            assertFalse(SecuredFlow.isSecured(s), s.scenarioId());
        }
    }

    @Test
    void securedDefaultsApplyOnlyToTheSecuredFlowAndExplicitSettingsWin() {
        E2eConfig unsecured = E2eConfig.load(new HashMap<>(Map.of("e2e.profile", "local")));
        assertEquals("classpath:scenarios.csv", unsecured.get("e2e.scenario-file"));
        assertEquals(20, unsecured.getInt("e2e.accounts"));

        E2eConfig secured = E2eConfig.load(new HashMap<>(Map.of("e2e.profile", "local", "e2e.flow", "SECURED")));
        assertEquals("classpath:secured-scenarios.csv", secured.get("e2e.scenario-file"));
        assertEquals(5, secured.getInt("e2e.accounts"));
        assertEquals(60, secured.getInt("e2e.cohort-a-percent"));
        assertEquals(100, secured.getInt("e2e.max-sim-days"));

        E2eConfig explicit = E2eConfig.load(new HashMap<>(Map.of("e2e.profile", "local", "e2e.flow", "SECURED", "e2e.accounts", "3")));
        assertEquals(3, explicit.getInt("e2e.accounts"));
    }

    @Test
    void securedDefaultAccountsAreSpreadOverTheMatrix() {
        List<Scenario> out = ScenarioLoader.expand(ScenarioLoader.load("classpath:secured-scenarios.csv"), 5, 60);
        assertEquals(5, out.size());
        assertEquals(3, out.stream().filter(s -> s.cohort() == Scenario.Cohort.A).count());
        assertEquals(5, out.stream().map(Scenario::scenarioId).distinct().count());
    }

    @Test
    void theSecuredFlowUsesMortgageProductsAndTheUnsecuredFlowDoesNot() throws Exception {
        var secured = ProductResolver.resolve(products(), List.of(sc("S1", "HL01")), true, true);
        assertEquals("HL01", secured.scenarios().get(0).productCode());

        // a secured scenario that names an unsecured product is moved to the mortgage product, never run unsecured
        var moved = ProductResolver.resolve(products(), List.of(sc("S2", "PL01")), true, true);
        assertEquals("HL01", moved.scenarios().get(0).productCode());

        // with no mortgage product the secured scenario is skipped, not run on an unsecured product
        JsonNode onlyUnsecured = M.readTree("[" + products().get(0).toString() + "]");
        var none = ProductResolver.resolve(onlyUnsecured, List.of(sc("S3", "HL01")), true, true);
        assertEquals(0, none.usable());

        // the unsecured flow still refuses a mortgage product
        var unsecured = ProductResolver.resolve(products(), List.of(sc("U1", "HL01")), true, false);
        assertEquals("PL01", unsecured.scenarios().get(0).productCode());
    }

    @Test
    void everySecuredAndSetupCheckIsInTheCatalogue() {
        Findings f = new Findings();
        for (int i = 1; i <= 12; i++) {
            f.def(String.format("SEC-%02d", i));
        }
        f.def("SETUP-03");
        f.def("SETUP-04");
    }

    @Test
    void lcmHostIsCoveredByTheProductionGuard() {
        Map<String, String> o = new HashMap<>();
        o.put("e2e.profile", "local");
        o.put("e2e.env.name", "local");
        o.put("e2e.lending.base-url", "http://localhost:8080");
        o.put("e2e.batch.base-url", "http://localhost:8081");
        o.put("e2e.admin.base-url", "http://localhost:8082");
        o.put("e2e.lcm.base-url", "https://lcm-prod.example.com");
        assertFalse(EnvironmentGuard.evaluate(E2eConfig.load(o)).allowed());
        o.put("e2e.lcm.base-url", "http://localhost:8084");
        assertTrue(EnvironmentGuard.evaluate(E2eConfig.load(o)).allowed());
    }

    @Test
    void lcmCleanupScriptIsScopedToTheRunsReferences() {
        RunContext c = RunContext.create(9_000_000_000_000L, Path.of("target/test-results"));
        String sql = Teardown.lcmCleanupSql(c);
        assertTrue(sql.contains("LIKE '" + c.runId() + "-%'"));
        assertTrue(sql.contains("BEGIN;") && sql.contains("COMMIT;"));
        assertFalse(sql.toLowerCase().contains("truncate"));
        for (String line : sql.split("\n")) {
            if (line.startsWith("DELETE")) {
                assertTrue(line.contains("WHERE"), line);
                assertTrue(line.contains(c.runId()), line);
            }
        }
        assertTrue(Teardown.cleanupSql(c).contains("DELETE FROM limit_outbox"));
    }
}
