package com.bank.lending.admin.web;

import com.bank.lending.admin.service.LcmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring-free: the limit screens forward to LCM under the right path and name, and reject malformed input first. */
class LimitsControllerTest {

    record Call(HttpMethod method, String path, Map<String, ?> query, Object body, String actor) {}

    /** An LcmClient that records what it is asked instead of calling LCM. */
    static class Recording extends LcmClient {
        final List<Call> calls = new ArrayList<>();

        Recording() {
            super("", "lcm-service", "");
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public JsonNode exchange(HttpMethod method, String path, Map<String, ?> query, Object body, String actor) {
            calls.add(new Call(method, path, query, body, actor));
            return null;
        }

        Call last() {
            return calls.get(calls.size() - 1);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Recording lcm = new Recording();
    private final LimitsController controller = new LimitsController(lcm);
    private final Principal admin = () -> "admin";

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theNameTypedOnTheScreenIsWhatLcmSeesAndTheLoginIsTheFallback() {
        controller.approveChange(7, null, "checker.anita", admin);
        assertThat(lcm.last().actor()).isEqualTo("checker.anita");
        assertThat(lcm.last().path()).isEqualTo("/facilities/changes/7/approve");

        controller.rejectChange(7, null, null, admin);
        assertThat(lcm.last().actor()).isEqualTo("admin");

        controller.activate("COL-1", "  ", admin);
        assertThat(lcm.last().actor()).isEqualTo("admin");
    }

    @Test
    void aBadActingAsNameNeverReachesLcm() {
        assertThatThrownBy(() -> controller.approveChange(7, null, "a b; drop", admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Acting-as");
        assertThatThrownBy(() -> controller.approveChange(7, null, "x", admin)).isInstanceOf(IllegalArgumentException.class);
        assertThat(lcm.calls).isEmpty();
    }

    @Test
    void referencesAndStatusesAreCheckedBeforeTheyAreUsedInAPath() {
        assertThatThrownBy(() -> controller.facility("../actuator")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.facility("a b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.facilities(null, "pending", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.changes("PENDING; x", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(lcm.calls).isEmpty();

        controller.facility("SANC-900001");
        assertThat(lcm.last().path()).isEqualTo("/facilities/SANC-900001");
    }

    @Test
    void searchFiltersAreForwardedAndEmptyOnesAreNull() {
        controller.facilities(101L, "ACTIVE", null);
        assertThat(lcm.last().path()).isEqualTo("/facilities");
        assertThat(lcm.last().query()).containsEntry("customerId", 101L).containsEntry("status", "ACTIVE");
        assertThat(lcm.last().query().get("parentRef")).isNull();

        controller.valuationsByStatus("PENDING");
        assertThat(lcm.last().path()).isEqualTo("/valuations");
        assertThat(lcm.last().query()).containsEntry("status", "PENDING");

        controller.excessRequests(null);
        assertThat(lcm.last().path()).isEqualTo("/facilities/excess-requests");
        assertThat(lcm.last().query().get("status")).isNull();
    }

    @Test
    void creatingAFacilityNeedsTheBasicsAndPassesTheBodyThrough() {
        assertThatThrownBy(() -> controller.createFacility(json("{\"customerId\":1}"), "maker.ravi", admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("facilityType");
        assertThatThrownBy(() -> controller.createFacility(json("[]"), "maker.ravi", admin))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(lcm.calls).isEmpty();

        JsonNode body = json("{\"customerId\":101,\"facilityType\":\"OVERALL\",\"currencyCode\":\"INR\",\"sanctionedAmount\":5000000,\"expiryDate\":\"2027-12-31\"}");
        controller.createFacility(body, "maker.ravi", admin);
        assertThat(lcm.last().method()).isEqualTo(HttpMethod.POST);
        assertThat(lcm.last().path()).isEqualTo("/facilities");
        assertThat(lcm.last().body()).isSameAs(body);
        assertThat(lcm.last().actor()).isEqualTo("maker.ravi");
    }

    @Test
    void valuationAndAllocationBodiesAreChecked() {
        assertThatThrownBy(() -> controller.proposeValuation("COL-1", json("{\"valuationDate\":\"2026-10-01\"}"), "m.one", admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("marketValue");
        assertThatThrownBy(() -> controller.allocate("COL-1", json("{\"facilityRef\":\"F 1\",\"allocationPct\":50}"), "m.one", admin))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.allocate("COL-1", json("{\"facilityRef\":\"F-1\"}"), "m.one", admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allocationPct");
        assertThat(lcm.calls).isEmpty();

        controller.allocate("COL-1", json("{\"facilityRef\":\"F-1\",\"allocationPct\":50}"), "m.one", admin);
        assertThat(lcm.last().path()).isEqualTo("/collateral/COL-1/allocations");
    }

    @Test
    void statusReportsWhetherLcmIsConfigured() {
        assertThat(controller.status()).containsEntry("enabled", true);
    }
}
