package com.bank.lending.chit.platform;

import com.bank.lending.chit.calendar.WorkingDayCalendar;
import com.bank.lending.chit.common.ChitException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the client against a throw-away local HTTP server that plays lending-service. */
class LendingPlatformClientTest {

    private HttpServer server;
    private final List<String> seen = new ArrayList<>();
    private final List<String> authSeen = new ArrayList<>();

    private LendingPlatformClient start(java.util.function.Function<String, int[]> statusFor, java.util.function.Function<String, String> bodyFor,
                                        String user, int retries) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String pq = ex.getRequestURI().toString();
            synchronized (seen) {
                seen.add(pq);
                authSeen.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            }
            int status = statusFor.apply(pq)[0];
            byte[] body = bodyFor.apply(pq).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new LendingPlatformClient(new LendingPlatformClient.Settings(url, user, "pw", Duration.ofSeconds(2), Duration.ofSeconds(5), retries));
    }

    private void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsTheBusinessDate() throws Exception {
        try {
            LendingPlatformClient c = start(p -> new int[]{200},
                    p -> "{\"businessDate\":\"2026-10-12\",\"status\":\"OPEN\",\"workingDay\":true,\"somethingNew\":1}", "", 0);
            assertEquals(LocalDate.of(2026, 10, 12), c.today());
            assertEquals("/api/v1/business-date", seen.get(0));
            assertEquals("null", authSeen.get(0), "no credentials configured, so no Authorization header is sent");
        } finally {
            stop();
        }
    }

    @Test
    void sendsBasicCredentialsWhenConfigured() throws Exception {
        try {
            LendingPlatformClient c = start(p -> new int[]{200}, p -> "{\"businessDate\":\"2026-10-12\"}", "chit", 0);
            c.today();
            assertTrue(authSeen.get(0).startsWith("Basic "));
        } finally {
            stop();
        }
    }

    @Test
    void asksLendingServiceWhetherADateIsAWorkingDay() throws Exception {
        try {
            LendingPlatformClient c = start(p -> new int[]{200},
                    p -> "{\"date\":\"x\",\"workingDay\":" + (p.endsWith("2026-10-17") ? "false" : "true") + "}", "", 0);
            assertFalse(c.isWorkingDay(LocalDate.of(2026, 10, 17)));
            assertTrue(c.isWorkingDay(LocalDate.of(2026, 10, 19)));
            assertEquals("/api/v1/business-date/working-day?date=2026-10-17", seen.get(0));
        } finally {
            stop();
        }
    }

    @Test
    void calendarAsksOncePerDate() throws Exception {
        try {
            LendingPlatformClient c = start(p -> new int[]{200}, p -> "{\"workingDay\":true}", "", 0);
            WorkingDayCalendar cal = c.calendar();
            cal.isWorkingDay(LocalDate.of(2026, 11, 2));
            cal.isWorkingDay(LocalDate.of(2026, 11, 2));
            cal.isWorkingDay(LocalDate.of(2026, 11, 3));
            assertEquals(2, seen.size());
        } finally {
            stop();
        }
    }

    @Test
    void retriesAServiceUnavailableOnceThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try {
            LendingPlatformClient c = start(p -> new int[]{calls.incrementAndGet() == 1 ? 503 : 200},
                    p -> "{\"businessDate\":\"2026-10-12\"}", "", 1);
            assertEquals(LocalDate.of(2026, 10, 12), c.today());
            assertEquals(2, seen.size());
        } finally {
            stop();
        }
    }

    @Test
    void doesNotRetryAClientError() throws Exception {
        try {
            LendingPlatformClient c = start(p -> new int[]{404}, p -> "{}", "", 3);
            ChitException e = assertThrows(ChitException.class, c::today);
            assertEquals("BUSINESS_DATE_UNAVAILABLE", e.code());
            assertEquals(1, seen.size());
        } finally {
            stop();
        }
    }

    @Test
    void persistentOutageGivesBusinessDateUnavailable() throws Exception {
        try {
            LendingPlatformClient c = start(p -> new int[]{503}, p -> "{}", "", 1);
            ChitException e = assertThrows(ChitException.class, c::today);
            assertEquals("BUSINESS_DATE_UNAVAILABLE", e.code());
            assertEquals(2, seen.size(), "one try plus one retry");
        } finally {
            stop();
        }
    }

    @Test
    void anUnreachableServiceGivesBusinessDateUnavailable() {
        LendingPlatformClient c = new LendingPlatformClient(new LendingPlatformClient.Settings("http://127.0.0.1:1", "", "",
                Duration.ofMillis(500), Duration.ofMillis(500), 0));
        assertEquals("BUSINESS_DATE_UNAVAILABLE", assertThrows(ChitException.class, c::today).code());
    }

    @Test
    void aReplyWithoutTheFieldIsRefusedNotGuessed() throws Exception {
        try {
            LendingPlatformClient c = start(p -> new int[]{200}, p -> "{\"status\":\"OPEN\"}", "", 0);
            assertEquals("BUSINESS_DATE_UNAVAILABLE", assertThrows(ChitException.class, c::today).code());
            assertEquals("BUSINESS_DATE_UNAVAILABLE", assertThrows(ChitException.class, () -> c.isWorkingDay(LocalDate.of(2026, 10, 12))).code());
        } finally {
            stop();
        }
    }

    @Test
    void aReplyThatIsNotJsonIsRefused() throws Exception {
        try {
            LendingPlatformClient c = start(p -> new int[]{200}, p -> "<html>waking up</html>", "", 0);
            assertEquals("BUSINESS_DATE_UNAVAILABLE", assertThrows(ChitException.class, c::today).code());
        } finally {
            stop();
        }
    }
}
