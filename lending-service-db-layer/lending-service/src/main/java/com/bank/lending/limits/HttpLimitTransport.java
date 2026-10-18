package com.bank.lending.limits;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Component
public class HttpLimitTransport implements LimitTransport {

    private final LimitSettings settings;
    private final HttpClient http;
    private final String authorization;

    public HttpLimitTransport(LimitSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.connectTimeoutMs()))
                .build();
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((settings.username() + ":" + settings.password()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Response send(String method, String path, String idempotencyKey, String json) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(settings.baseUrl() + path))
                .timeout(Duration.ofMillis(settings.readTimeoutMs()))
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .header("X-Actor", "lending-service");
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        if ("GET".equals(method)) {
            b.GET();
        } else {
            b.header("Content-Type", "application/json")
                    .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        }
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling LCM", e);
        }
    }
}
