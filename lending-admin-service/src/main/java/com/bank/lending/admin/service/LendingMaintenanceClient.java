package com.bank.lending.admin.service;

import com.bank.lending.admin.common.FeatureDisabledException;
import com.bank.lending.admin.common.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Start-from-scratch operations. Clearing all data and creating the base products are done by
 * lending-service (/api/v1/maintenance); this service never writes to the database itself. The
 * clear is refused by lending-service unless it was started with LENDING_RESET_ENABLED=true.
 */
@Service
public class LendingMaintenanceClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final RestClient client;

    public LendingMaintenanceClient(@Value("${lending.service.base-url:}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(180_000); // a sleeping free-tier service wakes in about a minute; truncating is quick after that
        this.client = enabled() ? RestClient.builder().baseUrl(this.baseUrl).requestFactory(factory).build() : null;
    }

    public boolean enabled() {
        return !baseUrl.isEmpty();
    }

    public JsonNode status() {
        require();
        return call(() -> client.get().uri("/api/v1/maintenance/status")
                .retrieve().onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                .body(JsonNode.class));
    }

    public JsonNode clearData(String actor) {
        return clearData(actor, false);
    }

    /**
     * @param ignoreEndOfDay true only when the batch service has just reported that nothing is running, so a business date still marked
     *                       as EOD_RUNNING belongs to an end of day that failed; lending-service then clears and reopens the date.
     */
    public JsonNode clearData(String actor, boolean ignoreEndOfDay) {
        require();
        ObjectNode body = JSON.createObjectNode().put("confirm", "RESET");
        if (ignoreEndOfDay) {
            body.put("ignoreEndOfDay", true);
        }
        return call(() -> client.post().uri("/api/v1/maintenance/clear-data")
                .header("X-Actor", actor == null ? "admin-portal" : actor)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                .body(JsonNode.class));
    }

    public JsonNode createBaseProducts() {
        require();
        return call(() -> client.post().uri("/api/v1/maintenance/base-products")
                .retrieve().onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                .body(JsonNode.class));
    }

    private void require() {
        if (!enabled()) {
            throw new FeatureDisabledException(
                    "Maintenance is not set up. Set LENDING_SERVICE_URL on the admin service to enable it.");
        }
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (UpstreamException e) {
            throw e;
        } catch (RestClientException e) {
            throw new UpstreamException(502, "Could not reach lending-service: " + e.getMessage());
        }
    }

    private static UpstreamException upstream(int status, java.io.InputStream body) {
        String text;
        try {
            text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "";
        }
        String msg = BatchConfigClient.message(text);
        return new UpstreamException(status, msg.isBlank() ? "lending-service returned HTTP " + status : msg);
    }
}
