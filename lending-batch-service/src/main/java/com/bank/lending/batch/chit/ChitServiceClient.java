package com.bank.lending.batch.chit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The batch service's way into the chit service: one call per daily job. The three jobs are safe to repeat for a day
 * (a second call changes nothing), which is what makes retrying on a timeout or a 502 safe. Only I/O errors and
 * 429/502/503/504 are retried; anything else, including 409 BUSINESS_DATE_MISMATCH, fails at once.
 *
 * <p>Not final so tests can substitute a hand-written fake.
 */
public class ChitServiceClient {

    /** The actor recorded in the chit service's audit trail for the nightly runs. */
    public static final String ACTOR = "lending-batch-service";

    /** One group the run could not finish; the others were done. */
    public record GroupFailure(String groupCode, String code, String message) {
    }

    /**
     * What a run did. {@code figures} depend on the job: cyclesOpened, demandsRaised, advanceUsed, dividendApplied
     * (demand run); lateFeesPosted, lateFeeTotal (late-fee run); ticketsDefaulted (default check).
     */
    public record RunResult(String job, String businessDate, int groupsProcessed, int groupsFailed,
                            Map<String, BigDecimal> figures, List<GroupFailure> failures) {

        public boolean clean() {
            return groupsFailed == 0 && (failures == null || failures.isEmpty());
        }

        public String figuresText() {
            if (figures == null || figures.isEmpty()) {
                return "no figures";
            }
            return figures.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().toPlainString()).collect(Collectors.joining(", "));
        }

        public String failuresText() {
            if (failures == null || failures.isEmpty()) {
                return "";
            }
            return failures.stream().map(f -> f.groupCode() + " (" + f.code() + ": " + f.message() + ")").collect(Collectors.joining("; "));
        }
    }

    private static final long MAX_BACKOFF_MILLIS = 30_000;

    private final ChitServiceSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ChitServiceClient(ChitServiceSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).version(HttpClient.Version.HTTP_1_1).build();
    }

    /** False when no URL was set; the jobs then skip with a warning. */
    public boolean configured() {
        return settings.configured();
    }

    /** POST /api/v1/chit/batch/demand-run: opens every cycle that has fallen due and raises the instalments. */
    public RunResult runDemands(LocalDate businessDate) {
        return run("/api/v1/chit/batch/demand-run", businessDate);
    }

    /** POST /api/v1/chit/batch/late-fee-run: charges the late fee for each overdue instalment up to the date. */
    public RunResult runLateFees(LocalDate businessDate) {
        return run("/api/v1/chit/batch/late-fee-run", businessDate);
    }

    /** POST /api/v1/chit/batch/default-run: flags tickets that have breached their group's default threshold. */
    public RunResult runDefaultCheck(LocalDate businessDate) {
        return run("/api/v1/chit/batch/default-run", businessDate);
    }

    private RunResult run(String path, LocalDate businessDate) {
        if (!settings.configured()) {
            throw new ChitServiceException("The chit service URL is not set (CHIT_SERVICE_URL)", 0, null);
        }
        return call("POST", path + "?businessDate=" + businessDate, RunResult.class);
    }

    private <T> T call(String method, String pathAndQuery, Class<T> type) {
        URI uri = URI.create(settings.baseUrl() + pathAndQuery);
        ChitServiceException last = null;
        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                        .timeout(settings.requestTimeout())
                        .header("Accept", "application/json")
                        .header("X-Actor", ACTOR);
                if (settings.hasCredentials()) {
                    String token = settings.username() + ":" + (settings.password() == null ? "" : settings.password());
                    req.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
                }
                req.method(method, HttpRequest.BodyPublishers.noBody());
                HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    return parse(resp.body(), type, method, pathAndQuery);
                }
                last = new ChitServiceException(method + " " + pathAndQuery + " -> HTTP " + status + ": " + abbreviate(resp.body()), status, null);
                if (!isTransient(status)) {
                    throw last;
                }
            } catch (IOException e) {
                last = new ChitServiceException(method + " " + pathAndQuery + " failed: " + e, 0, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ChitServiceException(method + " " + pathAndQuery + " interrupted", 0, e);
            }
            if (attempt < settings.maxAttempts()) {
                sleep(Math.min(MAX_BACKOFF_MILLIS, settings.retryBackoff().toMillis() * (1L << Math.min(attempt - 1, 20))));
            }
        }
        throw last;
    }

    private <T> T parse(String body, Class<T> type, String method, String path) {
        if (body == null || body.isBlank()) {
            throw new ChitServiceException(method + " " + path + " returned an empty body", 200, null);
        }
        try {
            return mapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new ChitServiceException("Unreadable response from " + method + " " + path + ": " + abbreviate(body), 200, e);
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
