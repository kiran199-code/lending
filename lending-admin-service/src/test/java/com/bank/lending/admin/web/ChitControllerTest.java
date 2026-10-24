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
    void duesAndStatementsAreForwardedWithCheckedReferences() {
        controller.dues("UPCOMING", "G1", 14, 50, null);
        assertThat(chit.last().path()).isEqualTo("/dues");
        assertThat(chit.last().query()).containsEntry("view", "UPCOMING").containsEntry("groupCode", "G1")
                .containsEntry("withinDays", 14).containsEntry("limit", 50);
        controller.dues(null, " ", null, null, null);
        assertThat(chit.last().query().get("groupCode")).isNull();

        controller.ticketStatement("G1", 3);
        assertThat(chit.last().path()).isEqualTo("/groups/G1/tickets/3/statement");
        controller.lateFeePreview("G1", 3, 10);
        assertThat(chit.last().path()).isEqualTo("/groups/G1/tickets/3/late-fee-preview");
        assertThat(chit.last().query()).containsEntry("days", 10);
        controller.groupStatement("G1");
        assertThat(chit.last().path()).isEqualTo("/groups/G1/statement");
        controller.demands("G1", 3, "OPEN");
        assertThat(chit.last().query()).containsEntry("ticketNo", 3).containsEntry("status", "OPEN");
        controller.payments("G1", null);
        assertThat(chit.last().path()).isEqualTo("/groups/G1/payments");
        controller.payment("G1", "RCPT-12");
        assertThat(chit.last().path()).isEqualTo("/groups/G1/payments/RCPT-12");
        controller.defaultCases("G1", "OPEN");
        assertThat(chit.last().path()).isEqualTo("/groups/G1/default-cases");

        int before = chit.calls.size();
        assertThatThrownBy(() -> controller.dues("upcoming", null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.dues("OPEN", "../x", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.payment("G1", "a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThat(chit.calls).hasSize(before);
    }

    @Test
    void recordingAPaymentNeedsAPositiveAmountAndAChannel() {
        assertThatThrownBy(() -> controller.recordPayment("G1", 2, json("{\"channel\":\"UPI\"}"), "k", null, admin))
                .hasMessageContaining("amount");
        assertThatThrownBy(() -> controller.recordPayment("G1", 2, json("{\"amount\":\"500\",\"channel\":\"UPI\"}"), "k", null, admin))
                .hasMessageContaining("amount");
        assertThatThrownBy(() -> controller.recordPayment("G1", 2, json("{\"amount\":0,\"channel\":\"UPI\"}"), "k", null, admin))
                .hasMessageContaining("more than zero");
        assertThatThrownBy(() -> controller.recordPayment("G1", 2, json("{\"amount\":500}"), "k", null, admin))
                .hasMessageContaining("channel");
        assertThatThrownBy(() -> controller.recordPayment("G1", 2, json("{\"amount\":500,\"channel\":\"upi\"}"), "k", null, admin))
                .hasMessageContaining("channel");
        assertThat(chit.calls).isEmpty();

        controller.recordPayment("G1", 2, json("{\"amount\":500.50,\"channel\":\"UPI\",\"externalRef\":\"U1\"}"), "k9", "ravi", admin);
        assertThat(chit.last().method()).isEqualTo(HttpMethod.POST);
        assertThat(chit.last().path()).isEqualTo("/groups/G1/tickets/2/payments");
        assertThat(chit.last().key()).isEqualTo("k9");
        assertThat(chit.last().actor()).isEqualTo("ravi");
    }

    @Test
    void reversingNeedsAReasonAndTheDailyJobsCarryNoBodyOrKey() {
        assertThatThrownBy(() -> controller.reversePayment("G1", "RCPT-1", json("{}"), "k", null, admin)).hasMessageContaining("reason");
        controller.reversePayment("G1", "RCPT-1", json("{\"reason\":\"cheque bounced\"}"), "k", "meena", admin);
        assertThat(chit.last().path()).isEqualTo("/groups/G1/payments/RCPT-1/reverse");
        assertThat(chit.last().actor()).isEqualTo("meena");

        controller.demandRun(null, admin);
        assertThat(chit.last().path()).isEqualTo("/batch/demand-run");
        assertThat(chit.last().actor()).isEqualTo("admin");
        assertThat(chit.last().key()).isNull();
        assertThat(chit.last().body()).isNull();
        controller.lateFeeRun("ravi", admin);
        assertThat(chit.last().path()).isEqualTo("/batch/late-fee-run");
        controller.defaultRun(null, admin);
        assertThat(chit.last().path()).isEqualTo("/batch/default-run");
    }

    @Test
    void previewForwardsTheRulesWithoutAnActorOrKey() {
        controller.preview(json("{\"rules\":{\"chitValue\":20000}}"));
        assertThat(chit.last().path()).isEqualTo("/groups/preview");
        assertThat(chit.last().actor()).isNull();
        assertThat(chit.last().key()).isNull();
    }
}
