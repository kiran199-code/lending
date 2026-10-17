package com.bank.lending.admin.service;

import com.bank.lending.admin.common.FeatureDisabledException;
import com.bank.lending.admin.common.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Accounting inquiry and the few accounting actions the console offers (retry or waive a posting exception, run the
 * reconciliation), all through lending-service's own API (/api/v1/accounting). This service never reads or writes the
 * accounting tables itself, so what the console shows is exactly what lending-service would answer to anyone else.
 */
@Service
public class LendingAccountingClient {

    static final String ROOT = "/api/v1/accounting";
    static final String REFERENCE_ROOT = "/api/v1/reference";

    private final String baseUrl;
    private final RestClient client;

    public LendingAccountingClient(@Value("${lending.service.base-url:}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000); // a sleeping free-tier service needs about a minute to wake; a reconciliation reads every account
        this.client = enabled() ? RestClient.builder().baseUrl(this.baseUrl).requestFactory(factory).build() : null;
    }

    public boolean enabled() {
        return !baseUrl.isEmpty();
    }

    /** GET {@code /api/v1/accounting<path>} with the query parameters that are not null (values must already be validated). */
    public JsonNode get(String path, Map<String, ?> query) {
        return get(ROOT, path, query);
    }

    /** GET {@code /api/v1/reference<path>}: the currencies and branches accounts and journals are booked in. */
    public JsonNode reference(String path) {
        return get(REFERENCE_ROOT, path, Map.of());
    }

    private JsonNode get(String root, String path, Map<String, ?> query) {
        require();
        return call(() -> client.get().uri(b -> {
                    b.path(root + path);
                    query.forEach((k, v) -> {
                        if (v != null && !v.toString().isBlank()) {
                            b.queryParam(k, v);
                        }
                    });
                    return b.build();
                })
                .retrieve()
                .onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                .body(JsonNode.class));
    }

    /** POST {@code /api/v1/accounting<path>} acting as the given user. */
    public JsonNode post(String path, Map<String, ?> query, Object body, String actor) {
        require();
        return call(() -> {
            RestClient.RequestBodySpec spec = client.post().uri(b -> {
                        b.path(ROOT + path);
                        query.forEach((k, v) -> {
                            if (v != null && !v.toString().isBlank()) {
                                b.queryParam(k, v);
                            }
                        });
                        return b.build();
                    })
                    .header("X-Actor", actor == null ? "admin-portal" : actor)
                    .contentType(MediaType.APPLICATION_JSON);
            return (body == null ? spec : spec.body(body)).retrieve()
                    .onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                    .body(JsonNode.class);
        });
    }

    private void require() {
        if (!enabled()) {
            throw new FeatureDisabledException(
                    "Accounting screens are not set up. Set LENDING_SERVICE_URL on the admin service to enable them.");
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
