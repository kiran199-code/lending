package com.bank.lending.batch.lending;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The batch service's only way to change loan data: every write goes through lending-service's
 * REST API (the batch DB role is read-only on the lending tables, and lending-service owns the
 * business rules). One method per endpoint a batch job needs.
 *
 * <p>All calls used here are idempotent on the lending-service side (accrual per account and date,
 * classification and rate reset converge to the same state, foreclosure carries an
 * {@code Idempotency-Key}), which is what makes the retry on transient failures safe. Only I/O
 * errors and 429/502/503/504 are retried; any other non-2xx status fails immediately with a
 * {@link LendingServiceException}.
 *
 * <p>Not final so tests can substitute a hand-written fake.
 */
public class LendingServiceClient {

    private final LendingServiceSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public LendingServiceClient(LendingServiceSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        // Lenient copy: lending-service may add response fields without breaking the batch.
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.http = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    // ---- result shapes (only the fields the batch needs) -------------------------------------

    public record AccrualResult(Long accountId, String outcome, BigDecimal accruedAmount, String reason) {
        /** True when this call wrote a new accrual (as opposed to ALREADY_POSTED / SKIPPED). */
        public boolean posted() {
            return "POSTED".equals(outcome);
        }
    }

    public record DelinquencyResult(Long accountId, long daysPastDue, String previousStatus, String newStatus,
                                    boolean statusChanged, boolean skipped) {
    }

    public record RateResetResult(Long accountId, String outcome, BigDecimal previousRate, BigDecimal newRate,
                                  String reason) {
        public boolean reset() {
            return "RESET".equals(outcome);
        }
    }

    // ---- endpoints ---------------------------------------------------------------------------

    /** POST /api/v1/loan-accounts/{id}/interest-accruals?businessDate= */
    public AccrualResult accrueInterest(long accountId, LocalDate businessDate) {
        return call("POST", "/api/v1/loan-accounts/" + accountId + "/interest-accruals?businessDate=" + businessDate,
                null, null, AccrualResult.class);
    }

    /** POST /api/v1/loan-accounts/{id}/delinquency-classification */
    public DelinquencyResult classifyDelinquency(long accountId, LocalDate asOfDate) {
        return call("POST", "/api/v1/loan-accounts/" + accountId + "/delinquency-classification",
                Map.of("asOfDate", asOfDate.toString()), null, DelinquencyResult.class);
    }

    /**
     * POST /api/v1/loan-accounts/{id}/rate-reset
     *
     * @param benchmarkRateRef only reset the account if it references this benchmark; may be null
     */
    public RateResetResult resetRate(long accountId, LocalDate businessDate, String benchmarkRateRef) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("businessDate", businessDate.toString());
        if (benchmarkRateRef != null) {
            body.put("benchmarkRateRef", benchmarkRateRef);
        }
        return call("POST", "/api/v1/loan-accounts/" + accountId + "/rate-reset", body, null, RateResetResult.class);
    }

    /** POST /api/v1/ledger-outbox/push?batchSize= */
    public void pushLedgerOutbox(int batchSize) {
        call("POST", "/api/v1/ledger-outbox/push?batchSize=" + batchSize, null, null, JsonNode.class);
    }

    /** GET /api/v1/ledger-outbox/pending?batchSize= — number of PENDING entries, capped at {@code limit}. */
    public int countPendingLedgerEntries(int limit) {
        JsonNode pending = call("GET", "/api/v1/ledger-outbox/pending?batchSize=" + limit, null, null, JsonNode.class);
        return pending != null && pending.isArray() ? pending.size() : 0;
    }

    /** POST /api/v1/loan-accounts/{id}/payments/foreclosure (idempotent on {@code idempotencyKey}). */
    public JsonNode foreclose(long accountId, LocalDate foreclosureDate, String channel, String idempotencyKey) {
        return call("POST", "/api/v1/loan-accounts/" + accountId + "/payments/foreclosure",
                Map.of("foreclosureDate", foreclosureDate.toString(), "channel", channel),
                Map.of("Idempotency-Key", idempotencyKey), JsonNode.class);
    }

    // ---- plumbing ----------------------------------------------------------------------------

    private <T> T call(String method, String pathAndQuery, Object body, Map<String, String> headers, Class<T> type) {
        URI uri = URI.create(settings.baseUrl() + pathAndQuery);
        String json;
        try {
            json = body == null ? null : mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new LendingServiceException("Cannot serialise request for " + method + " " + pathAndQuery, 0, e);
        }

        LendingServiceException last = null;
        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                        .timeout(settings.requestTimeout())
                        .header("Accept", "application/json");
                if (settings.hasCredentials()) {
                    String token = settings.username() + ":" + (settings.password() == null ? "" : settings.password());
                    req.header("Authorization", "Basic "
                            + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
                }
                if (headers != null) {
                    headers.forEach(req::header);
                }
                if (json != null) {
                    req.header("Content-Type", "application/json");
                    req.method(method, HttpRequest.BodyPublishers.ofString(json));
                } else {
                    req.method(method, HttpRequest.BodyPublishers.noBody());
                }

                HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    return parse(resp.body(), type, method, pathAndQuery);
                }
                last = new LendingServiceException(method + " " + pathAndQuery + " -> HTTP " + status + ": "
                        + abbreviate(resp.body()), status, null);
                if (!isTransient(status)) {
                    throw last;
                }
            } catch (IOException e) {
                last = new LendingServiceException(method + " " + pathAndQuery + " failed: " + e, 0, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LendingServiceException(method + " " + pathAndQuery + " interrupted", 0, e);
            }
            if (attempt < settings.maxAttempts()) {
                sleep(settings.retryBackoff().toMillis() * (1L << (attempt - 1)));
            }
        }
        throw last;
    }

    private <T> T parse(String body, Class<T> type, String method, String path) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new LendingServiceException("Unreadable response from " + method + " " + path + ": " + abbreviate(body),
                    200, e);
        }
    }

    private static boolean isTransient(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
