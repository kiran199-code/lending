package com.bank.lending.admin.service;

import com.bank.lending.admin.common.FeatureDisabledException;
import com.bank.lending.admin.common.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
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
 * The limits and collateral screens, through lending-lcm-service's own API (/api/v1/lcm) with its service login.
 * This service never touches the lcm schema. Calls made on behalf of a person carry that person's name in X-Actor,
 * which is what LCM's maker-checker compares.
 */
@Service
public class LcmClient {

    static final String ROOT = "/api/v1/lcm";

    private final String baseUrl;
    private final String password;
    private final RestClient client;

    public LcmClient(@Value("${lcm.service.base-url:}") String baseUrl,
                     @Value("${lcm.service.username:lcm-service}") String username,
                     @Value("${lcm.service.password:}") String password) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.password = password == null ? "" : password;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(90_000); // a sleeping free-tier service needs about a minute to wake
        this.client = !this.baseUrl.isEmpty() && !this.password.isEmpty()
                ? RestClient.builder().baseUrl(this.baseUrl).requestFactory(factory)
                        .defaultHeaders(h -> h.setBasicAuth(username, this.password)).build()
                : null;
    }

    /** The limits screens need LCM's address and its service password. */
    public boolean enabled() {
        return !baseUrl.isEmpty() && !password.isEmpty();
    }

    public JsonNode get(String path, Map<String, ?> query) {
        return exchange(HttpMethod.GET, path, query, null, null);
    }

    public JsonNode post(String path, Map<String, ?> query, Object body, String actor) {
        return exchange(HttpMethod.POST, path, query, body, actor);
    }

    /** One call to {@code /api/v1/lcm<path>}; query parameters that are null or blank are left out. */
    public JsonNode exchange(HttpMethod method, String path, Map<String, ?> query, Object body, String actor) {
        if (!enabled()) {
            throw new FeatureDisabledException(
                    "Limit screens are not set up. Set LCM_SERVICE_URL and LCM_SERVICE_PASSWORD on the admin service to enable them.");
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
            throw new UpstreamException(502, "Could not reach lending-lcm-service: " + e.getMessage());
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
        return new UpstreamException(status, msg.isBlank() ? "lending-lcm-service returned HTTP " + status : msg);
    }
}
