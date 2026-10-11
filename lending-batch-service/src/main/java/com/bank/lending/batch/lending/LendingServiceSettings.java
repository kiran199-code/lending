package com.bank.lending.batch.lending;

import java.time.Duration;

/** Connection settings for {@link LendingServiceClient} (bound from {@code lending.service.*}). */
public record LendingServiceSettings(
        String baseUrl,
        String username,
        String password,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxAttempts,
        Duration retryBackoff
) {
    public LendingServiceSettings {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("lending.service.base-url must be set (env LENDING_SERVICE_URL)");
        }
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(5);
        requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(60);
        maxAttempts = Math.max(1, maxAttempts);
        retryBackoff = retryBackoff != null ? retryBackoff : Duration.ofMillis(300);
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank();
    }
}
