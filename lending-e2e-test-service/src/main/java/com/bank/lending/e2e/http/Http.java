package com.bank.lending.e2e.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/** Thin JDK HttpClient wrapper: base URL, optional HTTP Basic, JSON in/out, bounded retry on transient failures. */
public final class Http {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<Integer> TRANSIENT = Set.of(502, 503, 504);

    private final HttpClient client;
    private final String baseUrl;
    private final String authHeader;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final Duration retryBackoff;
    private long calls;

    public Http(String baseUrl, String username, String password, Duration connectTimeout, Duration requestTimeout,
                int maxRetries, Duration retryBackoff) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NORMAL).build();
        this.authHeader = username == null || username.isBlank() ? null
                : "Basic " + Base64.getEncoder().encodeToString((username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
        this.retryBackoff = retryBackoff;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public long callCount() {
        return calls;
    }

    public ApiResult get(String path) {
        return send("GET", path, null, Map.of());
    }

    public ApiResult post(String path, JsonNode body, Map<String, String> headers) {
        return send("POST", path, body, headers);
    }

    public ApiResult post(String path, JsonNode body) {
        return send("POST", path, body, Map.of());
    }

    public ApiResult put(String path, JsonNode body) {
        return send("PUT", path, body, Map.of());
    }

    private ApiResult send(String method, String path, JsonNode body, Map<String, String> headers) {
        String url = baseUrl + path;
        ApiResult last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            calls++;
            long t0 = System.nanoTime();
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                        .header("Accept", "application/json");
                if (authHeader != null) {
                    rb.header("Authorization", authHeader);
                }
                headers.forEach(rb::header);
                if (body != null) {
                    rb.header("Content-Type", "application/json");
                    rb.method(method, HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
                } else if ("POST".equals(method) || "PUT".equals(method)) {
                    rb.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    rb.method(method, HttpRequest.BodyPublishers.noBody());
                }
                HttpResponse<String> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                long ms = (System.nanoTime() - t0) / 1_000_000;
                JsonNode json = null;
                String raw = resp.body();
                if (raw != null && !raw.isBlank()) {
                    try {
                        json = MAPPER.readTree(raw);
                    } catch (IOException ignored) {
                        // non-JSON body (HTML error page, PDF...) - keep raw only
                    }
                }
                last = new ApiResult(resp.statusCode(), json, raw, ms, null, method, url);
                if (!TRANSIENT.contains(resp.statusCode())) {
                    return last;
                }
            } catch (IOException | InterruptedException | IllegalArgumentException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                last = new ApiResult(-1, null, null, ms, e.getClass().getSimpleName() + ": " + e.getMessage(), method, url);
                if (e instanceof IllegalArgumentException) {
                    return last;
                }
            }
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(retryBackoff.toMillis() * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return last;
                }
            }
        }
        return last;
    }
}
