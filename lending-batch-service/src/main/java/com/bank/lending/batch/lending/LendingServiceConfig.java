package com.bank.lending.batch.lending;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires {@link LendingServiceClient} from {@code lending.service.*} (see application.yml).
 * The base URL is required: without it the application fails fast at startup instead of every
 * business job failing later at runtime.
 */
@Configuration
public class LendingServiceConfig {

    @Bean
    public LendingServiceClient lendingServiceClient(
            ObjectMapper objectMapper,
            @Value("${lending.service.base-url}") String baseUrl,
            @Value("${lending.service.username:}") String username,
            @Value("${lending.service.password:}") String password,
            @Value("${lending.service.connect-timeout-seconds:5}") long connectTimeoutSeconds,
            @Value("${lending.service.request-timeout-seconds:60}") long requestTimeoutSeconds,
            @Value("${lending.service.max-attempts:4}") int maxAttempts,
            @Value("${lending.service.retry-backoff-millis:500}") long retryBackoffMillis) {
        return new LendingServiceClient(new LendingServiceSettings(
                baseUrl, username, password,
                Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(requestTimeoutSeconds),
                maxAttempts, Duration.ofMillis(retryBackoffMillis)), objectMapper);
    }
}
