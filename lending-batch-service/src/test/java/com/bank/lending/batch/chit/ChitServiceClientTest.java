package com.bank.lending.batch.chit;

import com.bank.lending.batch.chit.ChitServiceClient.RunResult;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spring-free: the client against a throw-away in-JVM HTTP server. */
class ChitServiceClientTest {

    record Seen(String method, String pathAndQuery, String authorization, String actor) {
    }

    private HttpServer server;
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());

    private ChitServiceClient start(Function<Integer, Object[]> responder, String username, int maxAttempts) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger counter = new AtomicInteger();
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String query = exchange.getRequestURI().getRawQuery();
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath() + (query != null ? "?" + query : ""),
                    exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("X-Actor")));
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

    private static ChitServiceClient client(String baseUrl, String username, int maxAttempts) {
        return new ChitServiceClient(new ChitServiceSettings(baseUrl, username, "secret", Duration.ofSeconds(2), Duration.ofSeconds(5),
                maxAttempts, Duration.ofMillis(1)), new ObjectMapper());
    }

    private void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static final String CLEAN = "{\"job\":\"CHIT_DEMAND_RAISE\",\"businessDate\":\"2026-11-06\",\"groupsProcessed\":2,\"groupsFailed\":0,"
            + "\"figures\":{\"cyclesOpened\":2,\"demandsRaised\":24,\"advanceUsed\":3000.00},\"failures\":[],\"extra\":\"ignored\"}";

    @Test
    void theDemandRunPostsTheDateAndReadsTheSummary() throws Exception {
        try {
            ChitServiceClient c = start(n -> new Object[]{200, CLEAN}, null, 1);
            RunResult r = c.runDemands(LocalDate.of(2026, 11, 6));
            assertEquals("POST", seen.get(0).method());
            assertEquals("/api/v1/chit/batch/demand-run?businessDate=2026-11-06", seen.get(0).pathAndQuery());
            assertEquals("lending-batch-service", seen.get(0).actor());
            assertNull(seen.get(0).authorization());
            assertTrue(r.clean());
            assertEquals(2, r.groupsProcessed());
            assertEquals("24", r.figures().get("demandsRaised").toPlainString());
            assertTrue(r.figuresText().contains("cyclesOpened=2"));
        } finally {
            stop();
        }
    }

    @Test
    void theOtherTwoJobsUseTheirOwnPaths() throws Exception {
        try {
            ChitServiceClient c = start(n -> new Object[]{200, CLEAN}, null, 1);
            c.runLateFees(LocalDate.of(2026, 11, 9));
            c.runDefaultCheck(LocalDate.of(2026, 11, 9));
            assertEquals("/api/v1/chit/batch/late-fee-run?businessDate=2026-11-09", seen.get(0).pathAndQuery());
            assertEquals("/api/v1/chit/batch/default-run?businessDate=2026-11-09", seen.get(1).pathAndQuery());
        } finally {
            stop();
        }
    }

    @Test
    void credentialsAreSentAsBasicWhenConfigured() throws Exception {
        try {
            ChitServiceClient c = start(n -> new Object[]{200, CLEAN}, "chit-service", 1);
            c.runDemands(LocalDate.of(2026, 11, 6));
            assertEquals("Basic Y2hpdC1zZXJ2aWNlOnNlY3JldA==", seen.get(0).authorization());
        } finally {
            stop();
        }
    }

    @Test
    void aSummaryWithFailedGroupsIsNotClean() throws Exception {
        try {
            ChitServiceClient c = start(n -> new Object[]{200, "{\"job\":\"CHIT_LATE_FEE\",\"businessDate\":\"2026-11-09\",\"groupsProcessed\":1,"
                    + "\"groupsFailed\":1,\"figures\":{},\"failures\":[{\"groupCode\":\"G-7\",\"code\":\"DATA_CONFLICT\",\"message\":\"changed\"}]}"}, null, 1);
            RunResult r = c.runLateFees(LocalDate.of(2026, 11, 9));
            assertFalse(r.clean());
            assertTrue(r.failuresText().contains("G-7 (DATA_CONFLICT: changed)"));
        } finally {
            stop();
        }
    }

    @Test
    void aTransientFailureIsRetriedAndThenSucceeds() throws Exception {
        try {
            ChitServiceClient c = start(n -> n < 3 ? new Object[]{502, "bad gateway"} : new Object[]{200, CLEAN}, null, 4);
            assertTrue(c.runDemands(LocalDate.of(2026, 11, 6)).clean());
            assertEquals(3, seen.size());
        } finally {
            stop();
        }
    }

    @Test
    void aBusinessDateMismatchFailsAtOnceWithoutRetry() throws Exception {
        try {
            ChitServiceClient c = start(n -> new Object[]{409, "{\"code\":\"BUSINESS_DATE_MISMATCH\",\"message\":\"x\"}"}, null, 4);
            ChitServiceException e = assertThrows(ChitServiceException.class, () -> c.runDemands(LocalDate.of(2026, 11, 6)));
            assertEquals(409, e.httpStatus());
            assertTrue(e.getMessage().contains("BUSINESS_DATE_MISMATCH"));
            assertEquals(1, seen.size());
        } finally {
            stop();
        }
    }

    @Test
    void anOutageAfterTheLastAttemptFails() throws Exception {
        try {
            ChitServiceClient c = start(n -> new Object[]{503, "down"}, null, 2);
            ChitServiceException e = assertThrows(ChitServiceException.class, () -> c.runDemands(LocalDate.of(2026, 11, 6)));
            assertEquals(503, e.httpStatus());
            assertEquals(2, seen.size());
        } finally {
            stop();
        }
    }

    @Test
    void anUnreadableAnswerIsAnError() throws Exception {
        try {
            ChitServiceClient c = start(n -> new Object[]{200, "<html>not json</html>"}, null, 1);
            assertThrows(ChitServiceException.class, () -> c.runDemands(LocalDate.of(2026, 11, 6)));
        } finally {
            stop();
        }
    }

    @Test
    void withNoUrlTheClientIsNotConfiguredAndRefusesToCall() {
        ChitServiceClient c = client("", null, 1);
        assertFalse(c.configured());
        assertThrows(ChitServiceException.class, () -> c.runDemands(LocalDate.of(2026, 11, 6)));
        assertFalse(client("   ", null, 1).configured());
        assertTrue(client("http://x/", null, 1).configured());
    }

    @Test
    void aTrailingSlashOnTheUrlIsDropped() {
        assertEquals("http://x", new ChitServiceSettings("http://x/", null, null, null, null, 1, null).baseUrl());
    }
}
