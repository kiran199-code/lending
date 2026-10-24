package com.bank.lending.batch.chit;

import java.time.Duration;

/**
 * Connection settings for {@link ChitServiceClient} (env {@code CHIT_SERVICE_URL}, {@code CHIT_SERVICE_USERNAME},
 * {@code CHIT_SERVICE_PASSWORD}). Unlike lending-service the chit service is optional: with no URL the chit jobs
 * report that they have nothing to call instead of failing, so end of day for the loans is never held up by a chit
 * service that has not been set up.
 */
public record ChitServiceSettings(
        String baseUrl,
        String username,
        String password,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxAttempts,
        Duration retryBackoff
) {
    public ChitServiceSettings {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "" : (baseUrl.trim().endsWith("/") ? baseUrl.trim().substring(0, baseUrl.trim().length() - 1) : baseUrl.trim());
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(5);
        // A run can touch many groups, and a free-plan service may need a minute to wake first.
        requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(240);
        maxAttempts = Math.max(1, maxAttempts);
        retryBackoff = retryBackoff != null ? retryBackoff : Duration.ofMillis(500);
    }

    public boolean configured() {
        return !baseUrl.isEmpty();
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank();
    }
}
