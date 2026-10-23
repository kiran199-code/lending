package com.bank.lending.chit.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Wires the lending-service client from {@code chit.platform.*} (environment: LENDING_SERVICE_URL and friends). */
@Configuration
public class PlatformConfig {

    @Bean
    public LendingPlatformClient lendingPlatformClient(
            @Value("${chit.platform.lending-url:http://localhost:8080}") String baseUrl,
            @Value("${chit.platform.username:}") String username,
            @Value("${chit.platform.password:}") String password,
            @Value("${chit.platform.connect-timeout-ms:5000}") long connectMs,
            @Value("${chit.platform.read-timeout-ms:90000}") long readMs,
            @Value("${chit.platform.retries:1}") int retries) {
        return new LendingPlatformClient(new LendingPlatformClient.Settings(baseUrl, username, password,
                Duration.ofMillis(connectMs), Duration.ofMillis(readMs), retries));
    }
}
