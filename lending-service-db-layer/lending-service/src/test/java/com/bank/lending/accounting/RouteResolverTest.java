package com.bank.lending.accounting;

import com.bank.lending.accounting.engine.RouteResolver;
import com.bank.lending.accounting.model.Defs.RouteDef;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteResolverTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 12);
    private static final LocalDate OLD = LocalDate.of(2000, 1, 1);

    private static RouteDef route(long id, String product, String stage, String ccy, String gl, int prio) {
        return new RouteDef(id, 1, "LOAN_PRINCIPAL", product, stage, ccy, gl, prio, OLD, null);
    }

    private static String pick(List<RouteDef> routes, String product, String stage, String ccy) {
        return RouteResolver.resolve(routes, 1, "LOAN_PRINCIPAL", product, stage, ccy, D)
                .map(RouteDef::glAccountCode).orElse("NONE");
    }

    @Test
    void mostSpecificRouteWinsInTheDocumentedOrder() {
        List<RouteDef> routes = List.of(
                route(1, null, null, null, "GENERIC", 0),
                route(2, null, null, "USD", "CCY", 0),
                route(3, null, "NPA_LOSS", null, "STAGE", 0),
                route(4, null, "NPA_LOSS", "USD", "STAGE_CCY", 0),
                route(5, "HL", null, null, "PRODUCT", 0),
                route(6, "HL", null, "USD", "PRODUCT_CCY", 0),
                route(7, "HL", "NPA_LOSS", null, "PRODUCT_STAGE", 0),
                route(8, "HL", "NPA_LOSS", "USD", "ALL", 0));
        assertThat(pick(routes, "HL", "NPA_LOSS", "USD")).isEqualTo("ALL");
        assertThat(pick(routes, "HL", "NPA_LOSS", "INR")).isEqualTo("PRODUCT_STAGE");
        assertThat(pick(routes, "HL", "ACTIVE", "USD")).isEqualTo("PRODUCT_CCY");
        assertThat(pick(routes, "HL", "ACTIVE", "INR")).isEqualTo("PRODUCT");
        assertThat(pick(routes, "PL", "NPA_LOSS", "USD")).isEqualTo("STAGE_CCY");
        assertThat(pick(routes, "PL", "NPA_LOSS", "INR")).isEqualTo("STAGE");
        assertThat(pick(routes, "PL", "ACTIVE", "USD")).isEqualTo("CCY");
        assertThat(pick(routes, "PL", "ACTIVE", "INR")).isEqualTo("GENERIC");
    }

    @Test
    void priorityBreaksTiesAndExpiredRoutesAreIgnored() {
        List<RouteDef> routes = List.of(
                route(1, null, null, null, "LOW", 1),
                route(2, null, null, null, "HIGH", 5),
                new RouteDef(3, 1, "LOAN_PRINCIPAL", null, null, null, "EXPIRED", 99, OLD, LocalDate.of(2026, 1, 1)));
        assertThat(pick(routes, "HL", "ACTIVE", "INR")).isEqualTo("HIGH");
    }

    @Test
    void noMatchGivesEmpty() {
        assertThat(pick(List.of(route(1, "HL", null, null, "X", 0)), "PL", "ACTIVE", "INR")).isEqualTo("NONE");
    }

    private static RouteDef branchRoute(long id, String branch, String product, String gl) {
        return new RouteDef(id, 1, "LOAN_PRINCIPAL", product, null, null, gl, 0, OLD, null, branch);
    }

    private static String pickAt(List<RouteDef> routes, String product, String branch) {
        return RouteResolver.resolve(routes, 1, "LOAN_PRINCIPAL", product, "ACTIVE", "INR", branch, D)
                .map(RouteDef::glAccountCode).orElse("NONE");
    }

    @Test
    void aRouteForABranchAppliesOnlyToThatBranchAndOutranksTheOthers() {
        List<RouteDef> routes = List.of(
                route(1, null, null, null, "GENERIC", 0),
                route(2, "HL", null, null, "PRODUCT", 0),
                branchRoute(3, "MUM01", null, "MUM_ALL"),
                branchRoute(4, "MUM01", "HL", "MUM_HL"));
        assertThat(pickAt(routes, "HL", "MUM01")).isEqualTo("MUM_HL");
        assertThat(pickAt(routes, "PL", "MUM01")).isEqualTo("MUM_ALL");
        assertThat(pickAt(routes, "HL", "ONLINE")).isEqualTo("PRODUCT");
        assertThat(pickAt(routes, "PL", "ONLINE")).isEqualTo("GENERIC");
    }

    @Test
    void routesWithoutABranchServeEveryBranchIncludingWhenNoneIsGiven() {
        List<RouteDef> routes = List.of(route(1, null, null, null, "GENERIC", 0));
        assertThat(pickAt(routes, "HL", "ONLINE")).isEqualTo("GENERIC");
        assertThat(pickAt(routes, "HL", "MUM01")).isEqualTo("GENERIC");
        assertThat(pick(routes, "HL", "ACTIVE", "INR")).isEqualTo("GENERIC");
    }

    @Test
    void aBranchRouteIsNotUsedForAnotherBranch() {
        List<RouteDef> routes = List.of(branchRoute(1, "MUM01", null, "MUM_ALL"));
        assertThat(pickAt(routes, "HL", "ONLINE")).isEqualTo("NONE");
    }
}
