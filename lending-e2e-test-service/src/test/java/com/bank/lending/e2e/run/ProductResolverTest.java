package com.bank.lending.e2e.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bank.lending.e2e.scenario.Scenario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductResolverTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode products() throws Exception {
        return M.readTree("""
                [{"productCode":"PL01","status":"ACTIVE","accountType":"UNSECURED","scheduleType":"EMI","rateType":"FIXED",
                  "minSanctionedAmount":50000,"maxSanctionedAmount":5000000,"minTenureMonths":6,"maxTenureMonths":84,
                  "minInterestRate":8,"maxInterestRate":17,"repaymentFrequency":"MONTHLY","allowedRepaymentFrequencies":["MONTHLY"]},
                 {"productCode":"HL01","status":"ACTIVE","accountType":"MORTGAGE","scheduleType":"EMI","rateType":"FLOATING",
                  "minSanctionedAmount":500000,"maxSanctionedAmount":50000000,"minTenureMonths":60,"maxTenureMonths":360,
                  "repaymentFrequency":"MONTHLY","allowedRepaymentFrequencies":["MONTHLY"]},
                 {"productCode":"OLD1","status":"INACTIVE","accountType":"UNSECURED","scheduleType":"EMI","rateType":"FIXED",
                  "repaymentFrequency":"MONTHLY"}]""");
    }

    private static Scenario sc(String id, String product, String amount, int tenure, String freq, BigDecimal balloon) {
        return new Scenario(id, Scenario.Cohort.A, 1, id, product, new BigDecimal(amount), tenure, freq, null, "ACT_360", 3, balloon, null,
                Scenario.Behaviour.ON_TIME, Map.of(), 0, List.of());
    }

    @Test
    void existingProductIsKeptAndOnlyOutOfRangeValuesAreMovedInside() throws Exception {
        var r = ProductResolver.resolve(products(), List.of(sc("A", "PL01", "9000000", 100, null, null)), true);
        Scenario s = r.scenarios().get(0);
        assertEquals("PL01", s.productCode());
        assertEquals(0, new BigDecimal("5000000").compareTo(s.amount()));
        assertEquals(84, s.tenureMonths());
        assertEquals("ACT_360", s.dayCount()); // overrides kept on the product the scenario asked for
        assertTrue(r.skipped().isEmpty());
    }

    @Test
    void missingProductIsReplacedByAnExistingOneAndItsOverridesAreDropped() throws Exception {
        var r = ProductResolver.resolve(products(), List.of(sc("A", "AB01", "300000", 36, "QUARTERLY", new BigDecimal("30"))), true);
        Scenario s = r.scenarios().get(0);
        assertEquals("PL01", s.productCode());
        assertNull(s.balloonPercent());
        assertNull(s.dayCount());
        assertNull(s.moratoriumMonths());
        assertNull(s.repaymentFrequency());
        assertEquals(1, r.notes().size());
    }

    @Test
    void mortgageAndInactiveProductsAreNeverUsed() throws Exception {
        var r = ProductResolver.resolve(products(), List.of(sc("A", "HL01", "900000", 120, null, null), sc("B", "OLD1", "100000", 12, null, null)), true);
        assertEquals("PL01", r.scenarios().get(0).productCode());
        assertEquals("PL01", r.scenarios().get(1).productCode());
    }

    @Test
    void skipModeLeavesTheAccountOutAndTheOthersStay() throws Exception {
        var r = ProductResolver.resolve(products(), List.of(sc("A", "TL01", "300000", 36, null, null), sc("B", "PL01", "300000", 36, null, null)), false);
        assertNull(r.scenarios().get(0));
        assertNotNull(r.scenarios().get(1));
        assertEquals(1, r.skipped().size());
        assertEquals(1, r.usable());
    }

    @Test
    void noUsableProductAtAllSkipsEverythingInsteadOfFailing() throws Exception {
        var r = ProductResolver.resolve(M.readTree("[]"), List.of(sc("A", "TL01", "300000", 36, null, null)), true);
        assertNull(r.scenarios().get(0));
        assertEquals(0, r.usable());
    }

    @Test
    void tenureIsRoundedToAWholeNumberOfPeriodsWhenTheFrequencyIsKept() throws Exception {
        JsonNode p = M.readTree("""
                [{"productCode":"QL","status":"ACTIVE","accountType":"UNSECURED","scheduleType":"EMI","rateType":"FIXED",
                  "minTenureMonths":12,"maxTenureMonths":60,"repaymentFrequency":"QUARTERLY","allowedRepaymentFrequencies":["QUARTERLY"]}]""");
        var r = ProductResolver.resolve(p, List.of(sc("A", "QL", "100000", 14, null, null)), true);
        assertEquals(12, r.scenarios().get(0).tenureMonths());
    }
}
