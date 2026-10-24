package com.bank.lending.admin.service;

import com.bank.lending.admin.common.FeatureDisabledException;
import com.bank.lending.admin.common.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
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
 * The chit fund screens, through lending-chit-service's own API (/api/v1/chit) with its service login. This service never touches
 * the chit schema. Every state-changing call carries an Idempotency-Key (required by the chit service) and the acting person's name in
 * X-Actor (which the chit service records, and which its maker-checker on security verification compares).
 */
@Service
public class ChitClient {

    static final String ROOT = "/api/v1/chit";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String password;
    private final RestClient client;

    public ChitClient(@Value("${chit.service.base-url:}") String baseUrl,
                      @Value("${chit.service.username:chit-service}") String username,
                      @Value("${chit.service.password:}") String password) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.password = password == null ? "" : password;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        // A sleeping free-tier service needs about a minute to wake, and the chit service may itself wake lending-service
        // (business date) before it answers, so allow for two cold starts.
        factory.setReadTimeout(150_000);
        this.client = !this.baseUrl.isEmpty() && !this.password.isEmpty()
                ? RestClient.builder().baseUrl(this.baseUrl).requestFactory(factory)
                        .defaultHeaders(h -> h.setBasicAuth(username, this.password)).build()
                : null;
    }

    /** The chit screens need the chit service's address and its service password. */
    public boolean enabled() {
        return !baseUrl.isEmpty() && !password.isEmpty();
    }

    public JsonNode get(String path, Map<String, ?> query) {
        return exchange(HttpMethod.GET, path, query, null, null, null);
    }

    public JsonNode post(String path, Object body, String actor, String idempotencyKey) {
        return exchange(HttpMethod.POST, path, Map.of(), body, actor, idempotencyKey);
    }

    /** One call to {@code /api/v1/chit<path>}; query parameters that are null or blank are left out. */
    public JsonNode exchange(HttpMethod method, String path, Map<String, ?> query, Object body, String actor, String idempotencyKey) {
        if (!enabled()) {
            throw new FeatureDisabledException(
                    "Chit screens are not set up. Set CHIT_SERVICE_URL and CHIT_SERVICE_PASSWORD on the admin service to enable them.");
        }
        return call(() -> {
            RestClient.RequestBodySpec spec = client.method(method).uri(b -> {
                        b.path(ROOT + path);
                        query.forEach((k, v) -> {
                            if (v != null && !v.toString().isBlank()) {
                                b.queryParam(k, v);
                            }
                        });
                        return b.build();
                    })
                    .contentType(MediaType.APPLICATION_JSON);
            if (actor != null) {
                spec = spec.header("X-Actor", actor);
            }
            if (idempotencyKey != null) {
                spec = spec.header("Idempotency-Key", idempotencyKey);
            }
            return (body == null ? spec : spec.body(body)).retrieve()
                    .onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                    .body(JsonNode.class);
        });
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (UpstreamException e) {
            throw e;
        } catch (RestClientException e) {
            throw new UpstreamException(502, "Could not reach lending-chit-service: " + e.getMessage());
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
        return new UpstreamException(status, msg.isBlank() ? "lending-chit-service returned HTTP " + status : msg);
    }

    /**
     * The chit error body is {timestamp,status,code,message} (CONFIG_INVALID also lists violations). The code is kept in the text,
     * because it is what tells a failed smoke test apart: "BUSINESS_DATE_UNAVAILABLE: ...", "DATA_CONFLICT: ...".
     */
    static String message(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode n = JSON.readTree(body);
            if (n.isObject() && n.hasNonNull("message")) {
                StringBuilder sb = new StringBuilder();
                if (n.hasNonNull("code")) {
                    sb.append(n.get("code").asText()).append(": ");
                }
                sb.append(n.get("message").asText());
                JsonNode v = n.get("violations");
                if (v != null && v.isArray() && v.size() > 0) {
                    sb.append(" [");
                    for (int i = 0; i < v.size(); i++) {
                        sb.append(i > 0 ? "; " : "").append(v.get(i).isTextual() ? v.get(i).asText() : v.get(i).toString());
                    }
                    sb.append(']');
                }
                return sb.toString();
            }
        } catch (IOException ignored) {
            // not JSON
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }
}
