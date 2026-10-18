package com.bank.lending.limits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed calls to the LCM service. Every state-changing call carries an Idempotency-Key, so a retry after a timeout can never
 * apply twice. Transport failures, 5xx and 429 are retried (settings.retries) and then reported as LimitUnavailableException;
 * a 4xx answer from LCM is a business answer and is reported as LimitRejectedException without retrying.
 */
@Component
public class LimitClient {

    public static final String LOAN_ACCOUNT = "LOAN_ACCOUNT";
    private static final ObjectMapper JSON = new ObjectMapper();

    public record CheckResult(boolean allowed, String code, String message) {}

    public record ReservationResult(String reservationRef, String status, String wouldReject) {}

    private final LimitTransport transport;
    private final LimitSettings settings;

    public LimitClient(LimitTransport transport, LimitSettings settings) {
        this.transport = transport;
        this.settings = settings;
    }

    public CheckResult check(String facilityRef, BigDecimal amount, String currency, String branch, LocalDate businessDate) {
        return check(facilityRef, amount, currency, branch, businessDate, null);
    }

    /** With a customer id LCM also confirms the facility belongs to that customer (answer CUSTOMER_MISMATCH otherwise). */
    public CheckResult check(String facilityRef, BigDecimal amount, String currency, String branch, LocalDate businessDate,
                             Long customerId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("facilityRef", facilityRef);
        if (customerId != null) {
            body.put("customerId", customerId);
        }
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("branchCode", branch);
        body.put("businessDate", businessDate == null ? null : businessDate.toString());
        JsonNode r = call("POST", "/api/v1/lcm/utilisations/check", null, body);
        return new CheckResult(r.path("allowed").asBoolean(false), text(r, "rejectionCode"), text(r, "message"));
    }

    public ReservationResult reserve(String facilityRef, BigDecimal amount, String currency, String branch, long accountId,
                                     boolean enforce, int ttlMinutes, LocalDate businessDate, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("facilityRef", facilityRef);
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("branchCode", branch);
        body.put("referenceType", LOAN_ACCOUNT);
        body.put("referenceId", String.valueOf(accountId));
        body.put("ttlMinutes", ttlMinutes);
        body.put("enforce", enforce);
        body.put("businessDate", businessDate == null ? null : businessDate.toString());
        JsonNode r = call("POST", "/api/v1/lcm/utilisations/reserve", idempotencyKey, body);
        String ref = text(r, "reservationRef");
        if (ref == null) {
            throw new LimitUnavailableException("LCM reserve answered without a reservationRef");
        }
        return new ReservationResult(ref, text(r, "status"), text(r, "wouldReject"));
    }

    public void confirm(String reservationRef, BigDecimal amount, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        call("POST", "/api/v1/lcm/utilisations/reservations/" + reservationRef + "/confirm", idempotencyKey, body);
    }

    public void release(String reservationRef, String idempotencyKey) {
        call("POST", "/api/v1/lcm/utilisations/reservations/" + reservationRef + "/release", idempotencyKey, null);
    }

    public void repay(long accountId, BigDecimal amount, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referenceType", LOAN_ACCOUNT);
        body.put("referenceId", String.valueOf(accountId));
        body.put("amount", amount);
        call("POST", "/api/v1/lcm/utilisations/repay", idempotencyKey, body);
    }

    public void close(long accountId, String reason, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referenceType", LOAN_ACCOUNT);
        body.put("referenceId", String.valueOf(accountId));
        body.put("reason", reason);
        call("POST", "/api/v1/lcm/utilisations/close", idempotencyKey, body);
    }

    // ------------------------------------------------------------------------------------------------------------------

    private JsonNode call(String method, String path, String idempotencyKey, Map<String, Object> body) {
        String json = body == null ? null : write(body);
        LimitUnavailableException last = null;
        for (int attempt = 0; attempt <= settings.retries(); attempt++) {
            if (attempt > 0) {
                pause(150L * attempt);
            }
            LimitTransport.Response resp;
            try {
                resp = transport.send(method, path, idempotencyKey, json);
            } catch (IOException e) {
                last = new LimitUnavailableException("LCM not reachable: " + e.getMessage(), e);
                continue;
            }
            int s = resp.status();
            if (s >= 200 && s < 300) {
                return parse(resp.body());
            }
            if (s == 401 || s == 403) {
                throw new LimitUnavailableException("LCM rejected the service credential (HTTP " + s + ")");
            }
            if (s >= 500 || s == 429 || s == 408) {
                last = new LimitUnavailableException("LCM answered HTTP " + s);
                continue;
            }
            JsonNode err = parse(resp.body());
            String code = text(err, "code");
            throw new LimitRejectedException(code != null ? code : "HTTP_" + s, s, text(err, "message") != null ? text(err, "message") : "LCM answered HTTP " + s);
        }
        throw last != null ? last : new LimitUnavailableException("LCM call failed");
    }

    private static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new LimitUnavailableException("LCM answered with a body that is not JSON");
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
