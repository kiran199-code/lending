package com.bank.lending.batch.chit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires {@link ChitServiceClient} from environment variables. The URL is optional (see {@link ChitServiceSettings}):
 * the chit jobs are seeded switched off and skip with a warning when it is empty.
 */
@Configuration
public class ChitServiceConfig {

    @Bean
    public ChitServiceClient chitServiceClient(
            ObjectMapper objectMapper,
            @Value("${CHIT_SERVICE_URL:}") String baseUrl,
            @Value("${CHIT_SERVICE_USERNAME:}") String username,
            @Value("${CHIT_SERVICE_PASSWORD:}") String password,
            @Value("${CHIT_SERVICE_CONNECT_TIMEOUT_SECONDS:5}") long connectTimeoutSeconds,
            @Value("${CHIT_SERVICE_REQUEST_TIMEOUT_SECONDS:240}") long requestTimeoutSeconds,
            @Value("${CHIT_SERVICE_MAX_ATTEMPTS:3}") int maxAttempts,
            @Value("${CHIT_SERVICE_RETRY_BACKOFF_MILLIS:2000}") long retryBackoffMillis) {
        return new ChitServiceClient(new ChitServiceSettings(baseUrl, username, password,
                Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(requestTimeoutSeconds),
                maxAttempts, Duration.ofMillis(retryBackoffMillis)), objectMapper);
    }
}
