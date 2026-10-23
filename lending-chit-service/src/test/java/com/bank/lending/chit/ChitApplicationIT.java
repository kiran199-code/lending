package com.bank.lending.chit;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.ledger.JournalDraft;
import com.bank.lending.chit.ledger.JournalTemplates;
import com.bank.lending.chit.ledger.LedgerDtos.PostResult;
import com.bank.lending.chit.ledger.LedgerDtos.PostingContext;
import com.bank.lending.chit.ledger.LedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance over a real Postgres 16 (Testcontainers): the service starts, Flyway applies V1 to V3, the credential is
 * enforced, the ledger posts balanced and idempotent journals through the real repository, the trial balance and event
 * feed answer over HTTP, a group goes through create, open, enrol, activate and cancel over HTTP (with a small local
 * server standing in for lending-service's business date and calendar), and the reset empties everything.
 * Needs Docker.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chit.security.username=it", "chit.security.password=it-secret", "chit.maintenance.reset-enabled=true"})
class ChitApplicationIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Plays lending-service: business date 2026-10-12, and every Monday to Friday is a working day. */
    static final HttpServer LENDING = startLendingStub();

    private static HttpServer startLendingStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/business-date", ex -> {
                String path = ex.getRequestURI().getPath();
                String query = ex.getRequestURI().getQuery();
                String body;
                if (path.endsWith("/working-day") && query != null && query.startsWith("date=")) {
                    DayOfWeek day = LocalDate.parse(query.substring(5)).getDayOfWeek();
                    body = "{\"workingDay\":" + (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) + "}";
                } else {
                    body = "{\"businessDate\":\"2026-10-12\",\"status\":\"OPEN\",\"workingDay\":true}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("chit.platform.lending-url", () -> "http://127.0.0.1:" + LENDING.getAddress().getPort());
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerService ledger;
    @Autowired TransactionTemplate tx;

    private HttpEntity<Object> as(String actor, Object body) {
        return as(actor, null, body);
    }

    private HttpEntity<Object> as(String actor, String idempotencyKey, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth("it", "it-secret");
        h.setContentType(MediaType.APPLICATION_JSON);
        if (actor != null) {
            h.set("X-Actor", actor);
        }
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        return new HttpEntity<>(body, h);
    }

    private static Map<String, Object> twoTicketRules() {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("chitValue", 20000);
        r.put("ticketCount", 2);
        r.put("instalmentDueDay", 5);
        r.put("auctionDay", 10);
        r.put("startDate", "2026-11-01");
        r.put("commissionPct", 5);
        r.put("maxCommissionPct", 5);
        r.put("minDiscountPct", 5);
        r.put("maxDiscountPct", 40);
        return r;
    }

    private Map<String, Object> createGroup(String code, String key) {
        return call(HttpMethod.POST, "/api/v1/chit/groups",
                as("alice", key, Map.of("groupCode", code, "name", "IT group", "foremanRef", "F1", "rules", twoTicketRules())), HttpStatus.CREATED);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(HttpMethod m, String url, HttpEntity<Object> e, HttpStatus expected) {
        ResponseEntity<Map> r = rest.exchange(url, m, e, Map.class);
        assertThat(r.getStatusCode()).isEqualTo(expected);
        return r.getBody();
    }

    private long newGroupWithTicket(String code) {
        Long groupId = jdbc.queryForObject("INSERT INTO chit_group (group_code, name, foreman_ref, chit_value, ticket_count, cycle_count, "
                + "subscription, start_date, created_by) VALUES (?, 'IT', 'F1', 20000, 2, 2, 10000, '2026-11-01', 'it') RETURNING id", Long.class, code);
        jdbc.update("INSERT INTO chit_ticket (group_id, ticket_no, customer_id, created_by) VALUES (?, 1, 7, 'it')", groupId);
        return groupId;
    }

    private PostResult post(long groupId, JournalDraft d) {
        return tx.execute(s -> ledger.post(d, new PostingContext(groupId, LocalDate.of(2026, 11, 5), null, "IT", "1", "it")));
    }

    @Test
    void apiRequiresTheServiceCredentialButHealthDoesNot() {
        assertThat(rest.getForEntity("/api/v1/chit/events", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void flywayCreatedTheSchemaAndItsHistoryTable() {
        Integer tables = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'chit'", Integer.class);
        assertThat(tables).isGreaterThanOrEqualTo(26); // 25 tables + chit_schema_history
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chit_schema_history WHERE success", Integer.class)).isEqualTo(3);
    }

    @Test
    void ledgerPostsBalancedJournalsIdempotentlyAndReverses() {
        long groupId = newGroupWithTicket("IT-LEDGER");
        long ticketId = jdbc.queryForObject("SELECT id FROM chit_ticket WHERE group_id = ?", Long.class, groupId);

        PostResult demand = post(groupId, JournalTemplates.demand("IT:D1", ticketId, new BigDecimal("10000.00")));
        assertThat(demand.created()).isTrue();
        PostResult again = post(groupId, JournalTemplates.demand("IT:D1", ticketId, new BigDecimal("10000.00")));
        assertThat(again.created()).isFalse();
        assertThat(again.journalId()).isEqualTo(demand.journalId());

        assertThatThrownBy(() -> post(groupId, JournalTemplates.demand("IT:D1", ticketId, new BigDecimal("9999.00"))))
                .isInstanceOf(ChitException.class).hasMessageContaining("already used");

        post(groupId, JournalTemplates.payment("IT:P1", ticketId, new BigDecimal("10000.00")));

        Map<String, Object> tb = call(HttpMethod.GET, "/api/v1/chit/ledger/groups/" + groupId + "/trial-balance", as(null, null), HttpStatus.OK);
        assertThat(tb.get("inBalance")).isEqualTo(true);
        assertThat(new BigDecimal(tb.get("totalDebits").toString())).isEqualByComparingTo("20000.00");

        PostResult reversal = tx.execute(s -> ledger.reverse(demand.journalId(), "IT undo",
                new PostingContext(groupId, LocalDate.of(2026, 11, 6), null, "IT", "1", "it")));
        assertThat(reversal.created()).isTrue();
        assertThat(tx.execute(s -> ledger.reverse(demand.journalId(), "IT undo",
                new PostingContext(groupId, LocalDate.of(2026, 11, 6), null, "IT", "1", "it"))).created()).isFalse();

        Map<String, Object> journal = call(HttpMethod.GET, "/api/v1/chit/ledger/journals/" + demand.journalId(), as(null, null), HttpStatus.OK);
        assertThat((List<?>) journal.get("lines")).hasSize(2);
        call(HttpMethod.GET, "/api/v1/chit/ledger/journals/999999", as(null, null), HttpStatus.NOT_FOUND);
    }

    @Test
    void theLedgerCannotBeEditedBehindTheServicesBack() {
        long groupId = newGroupWithTicket("IT-IMMUTABLE");
        long ticketId = jdbc.queryForObject("SELECT id FROM chit_ticket WHERE group_id = ?", Long.class, groupId);
        post(groupId, JournalTemplates.demand("IT:IM1", ticketId, new BigDecimal("500.00")));
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_line SET amount = 1 WHERE journal_id IN "
                + "(SELECT id FROM ledger_journal WHERE posting_key = 'IT:IM1')")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_journal WHERE posting_key = 'IT:IM1'")).hasMessageContaining("append-only");
    }

    @Test
    void eventFeedReturnsCommittedEventsInOrderAndPagesByCursor() {
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO chit_event (event_type, aggregate_type, aggregate_ref, payload) VALUES ('IT_A','GROUP','1','{\"n\":1}')");
            jdbc.update("INSERT INTO chit_event (event_type, aggregate_type, aggregate_ref, payload) VALUES ('IT_B','GROUP','1','{\"n\":2}')");
        });
        Map<String, Object> page = call(HttpMethod.GET, "/api/v1/chit/events?limit=1000", as(null, null), HttpStatus.OK);
        List<?> events = (List<?>) page.get("events");
        assertThat(events.size()).isGreaterThanOrEqualTo(2);
        Map<String, Object> next = call(HttpMethod.GET, "/api/v1/chit/events?cursor=" + page.get("nextCursor"), as(null, null), HttpStatus.OK);
        assertThat((List<?>) next.get("events")).isEmpty();
        call(HttpMethod.GET, "/api/v1/chit/events?cursor=garbage", as(null, null), HttpStatus.BAD_REQUEST);
    }

    @Test
    void aGroupGoesFromDraftToActiveOverHttp() {
        Map<String, Object> created = createGroup("IT-FLOW", "it-create-1");
        assertThat(created.get("status")).isEqualTo("DRAFT");
        assertThat(created.get("seatsLeft")).isEqualTo(2);
        assertThat(createGroup("IT-FLOW", "it-create-1").get("replayed")).isEqualTo(true);
        call(HttpMethod.POST, "/api/v1/chit/groups", as("alice", "it-create-2",
                Map.of("groupCode", "IT-FLOW", "name", "dup", "foremanRef", "F1", "rules", twoTicketRules())), HttpStatus.CONFLICT);
        call(HttpMethod.POST, "/api/v1/chit/groups", as("alice", null,
                Map.of("groupCode", "IT-NOKEY", "name", "x", "foremanRef", "F1", "rules", twoTicketRules())), HttpStatus.BAD_REQUEST);

        Map<String, Object> badRules = twoTicketRules();
        badRules.put("subscription", 9999);
        Map<String, Object> refused = call(HttpMethod.POST, "/api/v1/chit/groups", as("alice", "it-create-3",
                Map.of("groupCode", "IT-BAD", "name", "x", "foremanRef", "F1", "rules", badRules)), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.get("code")).isEqualTo("CONFIG_INVALID");
        assertThat((List<?>) refused.get("violations")).isNotEmpty();

        call(HttpMethod.POST, "/api/v1/chit/groups/IT-FLOW/tickets", as("svc", "it-enrol-0", Map.of("customerId", 1)), HttpStatus.CONFLICT); // not open
        call(HttpMethod.POST, "/api/v1/chit/groups/IT-FLOW/open-enrolment", as("alice", "it-open-1", null), HttpStatus.OK);
        call(HttpMethod.POST, "/api/v1/chit/groups/IT-FLOW/tickets", as("svc", "it-enrol-1", Map.of("customerId", 1)), HttpStatus.CREATED);
        Map<String, Object> second = call(HttpMethod.POST, "/api/v1/chit/groups/IT-FLOW/tickets", as("svc", "it-enrol-2", Map.of("customerId", 1)), HttpStatus.CREATED);
        assertThat(second.get("ticketNo")).isEqualTo(2);
        assertThat(call(HttpMethod.GET, "/api/v1/chit/groups/IT-FLOW", as(null, null), HttpStatus.OK).get("status")).isEqualTo("FULL");

        Map<String, Object> check = call(HttpMethod.GET, "/api/v1/chit/groups/IT-FLOW/activation-check", as(null, null), HttpStatus.OK);
        assertThat(check.get("ready")).isEqualTo(true);
        Map<String, Object> active = call(HttpMethod.POST, "/api/v1/chit/groups/IT-FLOW/activate", as("alice", "it-activate-1", null), HttpStatus.OK);
        assertThat(((Map<?, ?>) active.get("group")).get("status")).isEqualTo("ACTIVE");
        assertThat((List<?>) active.get("cycles")).hasSize(2);
        assertThat(call(HttpMethod.POST, "/api/v1/chit/groups/IT-FLOW/activate", as("alice", "it-activate-1", null), HttpStatus.OK).get("replayed")).isEqualTo(true);
        call(HttpMethod.POST, "/api/v1/chit/groups/IT-FLOW/activate", as("alice", "it-activate-2", null), HttpStatus.CONFLICT);
        assertThat(rest.exchange("/api/v1/chit/groups/IT-FLOW/cycles", HttpMethod.GET, as(null, null), List.class).getBody()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chit_event WHERE event_type = 'ChitGroupActivated'", Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aGroupCanBeCancelledOverHttpAndTheCallNeedsAReason() {
        createGroup("IT-CXL", "it-cxl-create");
        call(HttpMethod.POST, "/api/v1/chit/groups/IT-CXL/cancel", as("alice", "it-cxl-1", Map.of("reason", " ")), HttpStatus.BAD_REQUEST);
        Map<String, Object> cancelled = call(HttpMethod.POST, "/api/v1/chit/groups/IT-CXL/cancel", as("alice", "it-cxl-2", Map.of("reason", "not viable")), HttpStatus.OK);
        assertThat(cancelled.get("status")).isEqualTo("CANCELLED");
        call(HttpMethod.POST, "/api/v1/chit/groups/IT-CXL/cancel", as("alice", "it-cxl-3", Map.of("reason", "again")), HttpStatus.CONFLICT);
        call(HttpMethod.GET, "/api/v1/chit/groups/NO-SUCH", as(null, null), HttpStatus.NOT_FOUND);
    }

    @Test
    void resetNeedsTheConfirmationWordThenEmptiesEverything() {
        newGroupWithTicket("IT-RESET");
        call(HttpMethod.POST, "/api/v1/chit/maintenance/clear-data", as("admin", Map.of("confirm", "no")), HttpStatus.BAD_REQUEST);
        Map<String, Object> status = call(HttpMethod.GET, "/api/v1/chit/maintenance/status", as(null, null), HttpStatus.OK);
        assertThat(status.get("resetEnabled")).isEqualTo(true);
        Map<String, Object> cleared = call(HttpMethod.POST, "/api/v1/chit/maintenance/clear-data", as("admin", Map.of("confirm", "RESET")), HttpStatus.OK);
        assertThat(cleared.get("clearedBy")).isEqualTo("admin");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chit_group", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_journal", Integer.class)).isZero();
    }
}
