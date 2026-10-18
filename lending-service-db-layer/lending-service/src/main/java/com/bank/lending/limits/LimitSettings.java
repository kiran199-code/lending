package com.bank.lending.limits;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Settings under lending.lcm.*. Everything defaults to "off": a deployment that sets nothing behaves exactly as before. */
@Component
public class LimitSettings {

    private final LimitMode mode;
    private final String baseUrl;
    private final String username;
    private final String password;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int retries;
    private final boolean failOpen;
    private final int reservationTtlMinutes;
    private final int retryBatch;
    private final int maxAttempts;
    private final int leaseMinutes;

    public LimitSettings(
            @Value("${lending.lcm.mode:off}") String mode,
            @Value("${lending.lcm.base-url:http://localhost:8084}") String baseUrl,
            @Value("${lending.lcm.username:lcm-service}") String username,
            @Value("${lending.lcm.password:changeme}") String password,
            @Value("${lending.lcm.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${lending.lcm.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${lending.lcm.retries:2}") int retries,
            @Value("${lending.lcm.fail-open:false}") boolean failOpen,
            @Value("${lending.lcm.reservation-ttl-minutes:30}") int reservationTtlMinutes,
            @Value("${lending.lcm.outbox.batch-size:50}") int retryBatch,
            @Value("${lending.lcm.outbox.max-attempts:10}") int maxAttempts,
            @Value("${lending.lcm.outbox.lease-minutes:5}") int leaseMinutes) {
        this.mode = LimitMode.parse(mode);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.username = username;
        this.password = password;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.retries = Math.max(0, retries);
        this.failOpen = failOpen;
        this.reservationTtlMinutes = reservationTtlMinutes;
        this.retryBatch = retryBatch;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.leaseMinutes = Math.max(1, leaseMinutes);
    }

    public LimitMode mode() { return mode; }
    public boolean enabled() { return mode != LimitMode.OFF; }
    public String baseUrl() { return baseUrl; }
    public String username() { return username; }
    public String password() { return password; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int readTimeoutMs() { return readTimeoutMs; }
    public int retries() { return retries; }
    /** Live mode only: when LCM cannot be reached, let the disbursement through (true) or stop it (false, the default). */
    public boolean failOpen() { return failOpen; }
    public int reservationTtlMinutes() { return reservationTtlMinutes; }
    public int retryBatch() { return retryBatch; }
    public int maxAttempts() { return maxAttempts; }
    public int leaseMinutes() { return leaseMinutes; }
}
