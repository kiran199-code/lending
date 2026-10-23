package com.bank.lending.chit.platform;

import com.bank.lending.chit.calendar.WorkingDayCalendar;
import com.bank.lending.chit.common.ChitException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads the business date and the working-day calendar from lending-service (read-only; nothing here writes there).
 *
 * <ul>
 *   <li>{@code GET /api/v1/business-date} gives {@code businessDate}.</li>
 *   <li>{@code GET /api/v1/business-date/working-day?date=} gives {@code workingDay}. The weekend set is configurable
 *       in lending-service, so the answer is always asked for, never worked out here.</li>
 * </ul>
 *
 * <p>Failure handling: only I/O errors and 502/503/504 are retried (lending-service on a free host can be asleep, so
 * the read timeout is long); any other failure, or a reply that cannot be read, is BUSINESS_DATE_UNAVAILABLE (503). A
 * failed call changes nothing in the chit service, so the caller can simply repeat the request.
 */
public class LendingPlatformClient implements BusinessDateSource {

    /** Connection settings. {@code username} may be blank when lending-service is open (it is, today). */
    public record Settings(String baseUrl, String username, String password, Duration connectTimeout, Duration readTimeout,
                           int retries) {
        public boolean hasCredentials() {
            return username != null && !username.isBlank();
        }
    }

    private static final Set<Integer> RETRYABLE = Set.of(502, 503, 504);

    private final Settings settings;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpClient http;

    public LendingPlatformClient(Settings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).version(HttpClient.Version.HTTP_1_1).build();
    }

    @Override
    public LocalDate today() {
        JsonNode body = get("/api/v1/business-date");
        JsonNode d = body.get("businessDate");
        if (d == null || d.isNull()) {
            throw unavailable("lending-service returned no businessDate");
        }
        try {
            return LocalDate.parse(d.asText());
        } catch (RuntimeException e) {
            throw unavailable("lending-service returned an unreadable businessDate: " + d.asText());
        }
    }

    public boolean isWorkingDay(LocalDate date) {
        JsonNode body = get("/api/v1/business-date/working-day?date=" + date);
        JsonNode w = body.get("workingDay");
        if (w == null || !w.isBoolean()) {
            throw unavailable("lending-service returned no workingDay for " + date);
        }
        return w.asBoolean();
    }

    /** A calendar that asks lending-service once per date and remembers the answer for the life of the returned object. */
    public WorkingDayCalendar calendar() {
        Map<LocalDate, Boolean> memo = new HashMap<>();
        return date -> memo.computeIfAbsent(date, this::isWorkingDay);
    }

    private JsonNode get(String pathAndQuery) {
        URI uri = URI.create(settings.baseUrl().replaceAll("/+$", "") + pathAndQuery);
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(settings.readTimeout()).header("Accept", "application/json").GET();
        if (settings.hasCredentials()) {
            String token = Base64.getEncoder().encodeToString(
                    (settings.username() + ":" + (settings.password() == null ? "" : settings.password())).getBytes(StandardCharsets.UTF_8));
            rb.header("Authorization", "Basic " + token);
        }
        HttpRequest request = rb.build();
        String last = "no attempt made";
        for (int attempt = 0; attempt <= Math.max(0, settings.retries()); attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status / 100 == 2) {
                    try {
                        return mapper.readTree(response.body());
                    } catch (IOException e) {
                        throw unavailable("lending-service returned a reply that is not JSON");
                    }
                }
                last = "HTTP " + status;
                if (!RETRYABLE.contains(status)) {
                    break;
                }
            } catch (IOException e) {
                last = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw unavailable("interrupted while calling lending-service");
            }
        }
        throw unavailable("lending-service could not be reached (" + last + ")");
    }

    private static ChitException unavailable(String why) {
        return new ChitException("BUSINESS_DATE_UNAVAILABLE", "The business date or calendar could not be read: " + why
                + ". Nothing was changed; try again.");
    }
}
