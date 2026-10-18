package com.bank.lending.lcm;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over HTTP against a real Postgres 16 (Testcontainers): Flyway, Spring wiring, security, JSON binding and the
 * main limit flow. Needs Docker. The service logic itself is covered by the unit tests and by the SQL-level scenario run
 * documented in the README.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "lcm.security.username=it", "lcm.security.password=it-secret", "lcm.monitoring.sweep-cron=-"})
class LcmApplicationIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired
    TestRestTemplate rest;

    private HttpEntity<Object> as(String actor, String idemKey, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth("it", "it-secret");
        h.setContentType(MediaType.APPLICATION_JSON);
        if (actor != null) h.set("X-Actor", actor);
        if (idemKey != null) h.set("Idempotency-Key", idemKey);
        return new HttpEntity<>(body, h);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(HttpMethod m, String url, HttpEntity<Object> e, HttpStatus expected) {
        ResponseEntity<Map> r = rest.exchange(url, m, e, Map.class);
        assertThat(r.getStatusCode()).isEqualTo(expected);
        return r.getBody();
    }

    @Test
    void apiRequiresTheServiceCredential() {
        ResponseEntity<String> r = rest.getForEntity("/api/v1/lcm/facilities", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void limitLifecycleOverHttp() {
        call(HttpMethod.POST, "/api/v1/lcm/facilities", as("maker1", null, Map.of("customerId", 1, "facilityRef", "IT-F1", "facilityType", "OVERALL",
                "currencyCode", "INR", "revolving", true, "sanctionedAmount", 1000, "expiryDate", "2099-12-31")), HttpStatus.CREATED);
        Map<String, Object> change = call(HttpMethod.POST, "/api/v1/lcm/facilities/IT-F1/changes",
                as("maker1", null, Map.of("changeType", "ACTIVATE", "reason", "it")), HttpStatus.CREATED);
        call(HttpMethod.POST, "/api/v1/lcm/facilities/changes/" + change.get("id") + "/approve", as("maker1", null, null), HttpStatus.FORBIDDEN);
        call(HttpMethod.POST, "/api/v1/lcm/facilities/changes/" + change.get("id") + "/approve", as("checker1", null, null), HttpStatus.OK);

        Map<String, Object> res = call(HttpMethod.POST, "/api/v1/lcm/utilisations/reserve", as(null, "it-k1", Map.of("facilityRef", "IT-F1", "amount", 600,
                "currency", "INR", "referenceType", "LOAN_ACCOUNT", "referenceId", "it-1")), HttpStatus.CREATED);
        assertThat(res.get("status")).isEqualTo("RESERVED");
        call(HttpMethod.POST, "/api/v1/lcm/utilisations/reserve", as(null, "it-k2", Map.of("facilityRef", "IT-F1", "amount", 500,
                "currency", "INR", "referenceType", "LOAN_ACCOUNT", "referenceId", "it-2")), HttpStatus.UNPROCESSABLE_ENTITY);
        Map<String, Object> replay = call(HttpMethod.POST, "/api/v1/lcm/utilisations/reserve", as(null, "it-k1", Map.of("facilityRef", "IT-F1", "amount", 600,
                "currency", "INR", "referenceType", "LOAN_ACCOUNT", "referenceId", "it-1")), HttpStatus.CREATED);
        assertThat(replay.get("reservationRef")).isEqualTo(res.get("reservationRef"));
        assertThat(replay.get("replayed")).isEqualTo(true);

        call(HttpMethod.POST, "/api/v1/lcm/utilisations/reservations/" + res.get("reservationRef") + "/confirm", as(null, "it-c1", null), HttpStatus.OK);
        Map<String, Object> repaid = call(HttpMethod.POST, "/api/v1/lcm/utilisations/repay", as(null, "it-p1",
                Map.of("referenceType", "LOAN_ACCOUNT", "referenceId", "it-1", "amount", 100)), HttpStatus.OK);
        assertThat(repaid.get("operation")).isEqualTo("REPAY");

        Map<String, Object> av = call(HttpMethod.GET, "/api/v1/lcm/facilities/IT-F1/availability", as(null, null, null), HttpStatus.OK);
        assertThat(((Number) av.get("utilised")).intValue()).isEqualTo(500);
        assertThat(((Number) av.get("available")).intValue()).isEqualTo(500);
        call(HttpMethod.GET, "/api/v1/lcm/monitoring/integrity", as(null, null, null), HttpStatus.OK);
        call(HttpMethod.GET, "/api/v1/lcm/events", as(null, null, null), HttpStatus.OK);
    }

    @Test
    void missingIdempotencyKeyIsABadRequest() {
        call(HttpMethod.POST, "/api/v1/lcm/utilisations/reserve", as(null, null, Map.of("facilityRef", "X", "amount", 1, "currency", "INR",
                "referenceType", "T", "referenceId", "1")), HttpStatus.BAD_REQUEST);
    }
}
