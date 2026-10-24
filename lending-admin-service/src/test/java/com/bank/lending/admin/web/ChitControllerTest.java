package com.bank.lending.admin.web;

import com.bank.lending.admin.service.ChitClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring-free: the chit screens forward to the chit service under the right path, name and idempotency key, and reject malformed input first. */
class ChitControllerTest {

    record Call(HttpMethod method, String path, Map<String, Object> query, Object body, String actor, String key) {}

    /** A ChitClient that records what it is asked instead of calling the chit service. */
    static class Recording extends ChitClient {
        final List<Call> calls = new ArrayList<>();

        Recording() {
            super("", "chit-service", "");
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public JsonNode exchange(HttpMethod method, String path, Map<String, ?> query, Object body, String actor, String idempotencyKey) {
            calls.add(new Call(method, path, query == null ? new LinkedHashMap<>() : new LinkedHashMap<String, Object>(query), body, actor,
                    idempotencyKey));
            return null;
        }

        Call last() {
            return calls.get(calls.size() - 1);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Recording chit = new Recording();
    private final ChitController controller = new ChitController(chit);
    private final Principal admin = () -> "admin";

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void creatingAGroupNeedsTheBasicsAndForwardsKeyAndActor() {
        assertThatThrownBy(() -> controller.create(json("{\"groupCode\":\"G1\",\"name\":\"n\"}"), "k1", null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("foremanRef");
        assertThatThrownBy(() -> controller.create(json("{\"groupCode\":\"a b\",\"name\":\"n\",\"foremanRef\":\"F\"}"), "k1", null, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("groupCode");
        assertThat(chit.calls).isEmpty();

        controller.create(json("{\"groupCode\":\"G1\",\"name\":\"n\",\"foremanRef\":\"F\",\"rules\":{}}"), "k1", "ravi", admin);
        assertThat(chit.last().method()).isEqualTo(HttpMethod.POST);
        assertThat(chit.last().path()).isEqualTo("/groups");
        assertThat(chit.last().key()).isEqualTo("k1");
        assertThat(chit.last().actor()).isEqualTo("ravi");
    }

    @Test
    void actorFallsBackToTheLoginAndAKeyIsGeneratedWhenNoneIsSent() {
        controller.activate("G1", null, null, admin);
        assertThat(chit.last().path()).isEqualTo("/groups/G1/activate");
        assertThat(chit.last().actor()).isEqualTo("admin");
        assertThat(chit.last().key()).isNotBlank();

        controller.activate("G1", "  ", " ", admin);
        assertThat(chit.last().key()).isNotBlank().isNotEqualTo("  ");

        assertThatThrownBy(() -> controller.activate("G1", "k", "x", admin)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enrolmentNeedsACustomerAndGoesToTheRightPaths() {
        assertThatThrownBy(() -> controller.enrol("G1", json("{}"), "k", null, admin)).hasMessageContaining("customerId");
        assertThatThrownBy(() -> controller.enrol("G1", json("{\"customerId\":\"7\"}"), "k", null, admin)).hasMessageContaining("customerId");
        controller.enrol("G1", json("{\"customerId\":7}"), "k", "ravi", admin);
        assertThat(chit.last().path()).isEqualTo("/groups/G1/tickets");

        controller.withdraw("G1", 2, json("{\"reason\":\"moved\"}"), "k2", "ravi", admin);
        assertThat(chit.last().path()).isEqualTo("/groups/G1/tickets/2/withdraw");

        controller.verifySecurity("G1", 2, "k3", "meena", admin);
        assertThat(chit.last().path()).isEqualTo("/groups/G1/tickets/2/security/verify");
        assertThat(chit.last().actor()).isEqualTo("meena");
        assertThat(chit.last().body()).isNull();
    }

    @Test
    void cancelNeedsAReason() {
        assertThatThrownBy(() -> controller.cancel("G1", json("{}"), "k", null, admin)).hasMessageContaining("reason");
        controller.cancel("G1", json("{\"reason\":\"not enough members\"}"), "k", null, admin);
        assertThat(chit.last().path()).isEqualTo("/groups/G1/cancel");
    }

    @Test
    void readsAreForwardedAndReferencesAreChecked() {
        controller.groups("ACTIVE", 10, null);
        assertThat(chit.last().path()).isEqualTo("/groups");
        assertThat(chit.last().query()).containsEntry("status", "ACTIVE").containsEntry("limit", 10);
        assertThat(chit.last().query().get("offset")).isNull();

        controller.tickets("G1", true);
        assertThat(chit.last().query()).containsEntry("includeExited", true);
        controller.quote("G1");
        assertThat(chit.last().path()).isEqualTo("/groups/G1/enrolment-quote");
        controller.activationCheck("G1");
        assertThat(chit.last().path()).isEqualTo("/groups/G1/activation-check");
        controller.cycles("G1");
        assertThat(chit.last().path()).isEqualTo("/groups/G1/cycles");

        int before = chit.calls.size();
        assertThatThrownBy(() -> controller.group("../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.groups("active", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(chit.calls).hasSize(before);
    }

    @Test
    void previewForwardsTheRulesWithoutAnActorOrKey() {
        controller.preview(json("{\"rules\":{\"chitValue\":20000}}"));
        assertThat(chit.last().path()).isEqualTo("/groups/preview");
        assertThat(chit.last().actor()).isNull();
        assertThat(chit.last().key()).isNull();
    }
}
