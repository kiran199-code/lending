package com.bank.lending.batch.lending;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spring-free: the client against a throw-away in-JVM HTTP server. */
class LendingServiceClientTest {

    /** What the stub saw. */
    record Seen(String method, String pathAndQuery, String body, String idempotencyKey, String authorization, String actor) {
    }

    private HttpServer server;
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());

    /** Starts a stub; {@code responder} maps a request number (1-based) and seen request to {status, body}. */
    private LendingServiceClient start(Function<Integer, Object[]> responder, String username, int maxAttempts) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger counter = new AtomicInteger();
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String query = exchange.getRequestURI().getRawQuery();
            seen.add(new Seen(exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath() + (query != null ? "?" + query : ""), body,
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-Actor")));
            Object[] r = responder.apply(counter.incrementAndGet());
            byte[] payload = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders((Integer) r[0], payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        });
        server.start();
        return client("http://127.0.0.1:" + server.getAddress().getPort(), username, maxAttempts);
    }

    private static LendingServiceClient client(String baseUrl, String username, int maxAttempts) {
        return new LendingServiceClient(new LendingServiceSettings(baseUrl, username, "secret",
                Duration.ofSeconds(2), Duration.ofSeconds(5), maxAttempts, Duration.ofMillis(1)), new ObjectMapper());
    }

    private void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void accrualCallsThePerAccountEndpointAndParsesTheOutcome() throws Exception {
        try {
            LendingServiceClient c = start(n -> new Object[]{200,
                    "{\"accountId\":7,\"outcome\":\"POSTED\",\"accruedAmount\":12.3456,\"extraField\":\"ignored\"}"}, null, 1);
            LendingServiceClient.AccrualResult r = c.accrueInterest(7, LocalDate.of(2026, 10, 12));
            assertTrue(r.posted());
            assertEquals("12.3456", r.accruedAmount().toPlainString());
            assertEquals("POST", seen.get(0).method());
            assertEquals("/api/v1/loan-accounts/7/interest-accruals?businessDate=2026-10-12", seen.get(0).pathAndQuery());
            assertEquals(null, seen.get(0).authorization());
        } finally {
            stop();
        }
    }

    @Test
    void anAlreadyPostedAccrualIsNotReportedAsPosted() throws Exception {
        try {
            LendingServiceClient c = start(n -> new Object[]{200, "{\"accountId\":7,\"outcome\":\"ALREADY_POSTED\"}"}, null, 1);
            assertFalse(c.accrueInterest(7, LocalDate.of(2026, 10, 12)).posted());
        } finally {
            stop();
        }
    }

    @Test
    void classificationSendsTheAsOfDateAndParsesTheBucketChange() throws Exception {
        try {
            LendingServiceClient c = start(n -> new Object[]{200,
                    "{\"accountId\":9,\"asOfDate\":\"2026-10-12\",\"daysPastDue\":45,\"previousStatus\":\"NPA_SMA0\","
                            + "\"newStatus\":\"NPA_SMA1\",\"statusChanged\":true,\"skipped\":false}"}, null, 1);
            LendingServiceClient.DelinquencyResult r = c.classifyDelinquency(9, LocalDate.of(2026, 10, 12));
            assertEquals(45L, r.daysPastDue());
            assertTrue(r.statusChanged());
            assertEquals("NPA_SMA1", r.newStatus());
            assertEquals("/api/v1/loan-accounts/9/delinquency-classification", seen.get(0).pathAndQuery());
            assertEquals("{\"asOfDate\":\"2026-10-12\"}", seen.get(0).body());
        } finally {
            stop();
        }
    }

    @Test
    void rateResetSendsTheBenchmarkOnlyWhenGiven() throws Exception {
        try {
            LendingServiceClient c = start(n -> new Object[]{200,
                    "{\"accountId\":3,\"outcome\":\"RESET\",\"previousRate\":9.5,\"newRate\":9.75}"}, null, 1);
            LendingServiceClient.RateResetResult r = c.resetRate(3, LocalDate.of(2026, 10, 12), "MCLR_1Y");
            assertTrue(r.reset());
            assertEquals("/api/v1/loan-accounts/3/rate-reset", seen.get(0).pathAndQuery());
            assertEquals("{\"businessDate\":\"2026-10-12\",\"benchmarkRateRef\":\"MCLR_1Y\"}", seen.get(0).body());
            c.resetRate(3, LocalDate.of(2026, 10, 12), null);
            assertEquals("{\"businessDate\":\"2026-10-12\"}", seen.get(1).body());
        } finally {
            stop();
        }
    }

    @Test
    void forecloseCarriesTheIdempotencyKey() throws Exception {
        try {
            LendingServiceClient c = start(n -> new Object[]{200, "{\"transactionType\":\"FORECLOSURE\"}"}, null, 1);
            JsonNode txn = c.foreclose(5, LocalDate.of(2026, 10, 12), "MANUAL", "FORECLOSURE-5-2026-10-12");
            assertEquals("FORECLOSURE", txn.get("transactionType").asText());
            assertEquals("FORECLOSURE-5-2026-10-12", seen.get(0).idempotencyKey());
            assertEquals("{\"foreclosureDate\":\"2026-10-12\",\"channel\":\"MANUAL\"}", seen.get(0).body());
        } finally {
            stop();
        }
    }

    @Test
    void ledgerOutboxPushAndPendingCount() throws Exception {
        try {
            LendingServiceClient c = start(n -> new Object[]{200, "[{\"outboxId\":1},{\"outboxId\":2},{\"outboxId\":3}]"}, null, 1);
            assertEquals(3, c.countPendingLedgerEntries(50));
            assertEquals("GET", seen.get(0).method());
            assertEquals("/api/v1/ledger-outbox/pending?batchSize=50", seen.get(0).pathAndQuery());
            c.pushLedgerOutbox(25);
            assertEquals("POST", seen.get(1).method());
            assertEquals("/api/v1/ledger-outbox/push?batchSize=25", seen.get(1).pathAndQuery());
        } finally {
            stop();
        }
    }

    @Test
    void credentialsAreSentAsBasicAuthWhenConfigured() throws Exception {
        try {
            LendingServiceClient c = start(n -> new Object[]{200, "[]"}, "svc", 1);
            c.countPendingLedgerEntries(1);
            // base64("svc:secret")
            assertEquals("Basic c3ZjOnNlY3JldA==", seen.get(0).authorization());
        } finally {
            stop();
        }
    }

    @Test
    void transientFailuresAreRetriedAndThenSucceed() throws Exception {
        try {
            LendingServiceClient c = start(n -> n < 3
                    ? new Object[]{503, "{\"message\":\"warming up\"}"}
                    : new Object[]{200, "{\"accountId\":1,\"outcome\":\"POSTED\"}"}, null, 4);
            assertTrue(c.accrueInterest(1, LocalDate.of(2026, 10, 12)).posted());
            assertEquals(3, seen.size());
        } finally {
            stop();
        }
    }

    @Test
    void persistentTransientFailureGivesUpAfterMaxAttempts() throws Exception {
        try {
            LendingServiceClient c = start(n -> new Object[]{503, "down"}, null, 3);
            LendingServiceException e = assertThrows(LendingServiceException.class,
                    () -> c.accrueInterest(1, LocalDate.of(2026, 10, 12)));
            assertEquals(503, e.httpStatus());
            assertEquals(3, seen.size());
        } finally {
            stop();
        }
    }

    @Test
    void clientErrorsAreNotRetriedAndAreSkippableByTheFramework() throws Exception {
        try {
            LendingServiceClient c = start(n -> new Object[]{404, "{\"message\":\"Loan account 99 not found\"}"}, null, 4);
            // IllegalStateException is what batch_job_config.skippable_exceptions lists.
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> c.accrueInterest(99, LocalDate.of(2026, 10, 12)));
            assertTrue(e instanceof LendingServiceException);
            assertEquals(404, ((LendingServiceException) e).httpStatus());
            assertTrue(e.getMessage().contains("Loan account 99 not found"));
            assertEquals(1, seen.size());
        } finally {
            stop();
        }
    }

    @Test
    void anUnreachableServiceFailsWithStatusZeroAfterRetries() {
        LendingServiceClient c = client("http://127.0.0.1:1", null, 2);
        LendingServiceException e = assertThrows(LendingServiceException.class,
                () -> c.accrueInterest(1, LocalDate.of(2026, 10, 12)));
        assertEquals(0, e.httpStatus());
    }

    @Test
    void businessDateEndpointsUseTheActorHeaderAndParseTheState() throws Exception {
        try {
            LendingServiceClient c = start(n -> new Object[]{200,
                    "{\"businessDate\":\"2026-10-12\",\"previousBusinessDate\":\"2026-10-11\",\"nextBusinessDate\":\"2026-10-13\","
                            + "\"status\":\"EOD_RUNNING\",\"workingDay\":true,\"systemDate\":\"2026-10-12\"}"}, null, 1);
            LendingServiceClient.BusinessDateInfo info = c.businessDate();
            assertEquals(LocalDate.of(2026, 10, 12), info.date());
            assertTrue(info.eodRunning());
            assertEquals("GET", seen.get(0).method());
            assertEquals("/api/v1/business-date", seen.get(0).pathAndQuery());

            c.startEndOfDay("scheduler");
            assertEquals("/api/v1/business-date/eod/start", seen.get(1).pathAndQuery());
            assertEquals("scheduler", seen.get(1).actor());

            c.advanceBusinessDate(LocalDate.of(2026, 10, 12), "scheduler");
            assertEquals("/api/v1/business-date/advance", seen.get(2).pathAndQuery());
            assertEquals("{\"expectedBusinessDate\":\"2026-10-12\"}", seen.get(2).body());
        } finally {
            stop();
        }
    }

    @Test
    void aBlankBaseUrlIsRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new LendingServiceSettings(" ", null, null, null, null, 1, null));
    }
}
