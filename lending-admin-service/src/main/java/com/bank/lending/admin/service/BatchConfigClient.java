package com.bank.lending.admin.service;

import com.bank.lending.admin.common.FeatureDisabledException;
import com.bank.lending.admin.common.UpstreamException;
import com.bank.lending.admin.dto.AdminDtos.BatchJobConfigUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The only write path of this application. Batch job settings are saved through
 * lending-batch-service's own validated admin API (PUT /jobs/{name}, POST /config/reload) using
 * that service's batch-admin login; this service never writes to the database itself.
 */
@Service
public class BatchConfigClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String username;
    private final String password;
    private final RestClient client;
    /** Same service, short timeout: status checks come every few seconds and must not hang the screen while the service wakes up. */
    private final RestClient statusClient;

    public BatchConfigClient(@Value("${batch.service.base-url:}") String baseUrl,
                             @Value("${batch.service.username:batch-admin}") String username,
                             @Value("${batch.service.password:}") String password) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.username = username;
        this.password = password == null ? "" : password;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(90_000); // a sleeping free-tier service needs about a minute to wake
        this.client = enabled()
                ? RestClient.builder().baseUrl(this.baseUrl).requestFactory(factory)
                        .defaultHeaders(h -> h.setBasicAuth(this.username, this.password)).build()
                : null;
        SimpleClientHttpRequestFactory quick = new SimpleClientHttpRequestFactory();
        quick.setConnectTimeout(10_000);
        quick.setReadTimeout(20_000);
        this.statusClient = enabled()
                ? RestClient.builder().baseUrl(this.baseUrl).requestFactory(quick)
                        .defaultHeaders(h -> h.setBasicAuth(this.username, this.password)).build()
                : null;
    }

    /** Editing needs the batch service address and its admin password. */
    public boolean enabled() {
        return !baseUrl.isEmpty() && !password.isEmpty();
    }

    public void update(String jobName, BatchJobConfigUpdate update) {
        require();
        call(() -> client.put().uri("/api/v1/batch-admin/jobs/{job}", jobName)
                .contentType(MediaType.APPLICATION_JSON).body(update)
                .retrieve().onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                .toBodilessEntity());
    }

    /** Makes the saved settings live: the batch service rebuilds its jobs and Quartz triggers. */
    public String reload() {
        require();
        return call(() -> {
            String body = client.post().uri("/api/v1/batch-admin/config/reload")
                    .retrieve().onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                    .body(String.class);
            return message(body);
        });
    }

    /** GET /api/v1/batch-admin/eod/status: running flag, business date, live progress and the last run. */
    public JsonNode eodStatus() {
        require();
        return call(() -> statusClient.get().uri("/api/v1/batch-admin/eod/status")
                .retrieve().onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                .body(JsonNode.class));
    }

    /**
     * POST /api/v1/batch-admin/eod/run: starts end of day, or resumes a failed one (202); 409 if one is already running or if the
     * business date is no longer {@code expectedBusinessDate} (the date the operator saw on screen).
     */
    public JsonNode eodRun(String expectedBusinessDate) {
        require();
        return call(() -> client.post().uri("/api/v1/batch-admin/eod/run?expectedBusinessDate={d}", expectedBusinessDate)
                .retrieve().onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                .body(JsonNode.class));
    }

    private void require() {
        if (!enabled()) {
            throw new FeatureDisabledException(
                    "Batch service access is not set up. Set BATCH_SERVICE_URL and BATCH_ADMIN_PASSWORD on the admin service to enable it.");
        }
    }

    private static <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (UpstreamException e) {
            throw e;
        } catch (RestClientException e) {
            throw new UpstreamException(502, "Could not reach lending-batch-service: " + e.getMessage());
        }
    }

    private static UpstreamException upstream(int status, java.io.InputStream body) {
        String text;
        try {
            text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "";
        }
        String msg = message(text);
        if (status == 401 || status == 403) {
            msg = "lending-batch-service rejected the admin login; check BATCH_ADMIN_USERNAME / BATCH_ADMIN_PASSWORD.";
        }
        return new UpstreamException(status, msg.isBlank() ? "lending-batch-service returned HTTP " + status : msg);
    }

    /** Pulls "message" out of a JSON body; falls back to the raw text. */
    static String message(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode n = JSON.readTree(body);
            if (n.hasNonNull("message")) return n.get("message").asText();
        } catch (IOException ignored) {
            // not JSON
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }
}
