package com.bank.lending.lcm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LcmSettings {
    private final boolean enforce;
    private final boolean makerCheckerEnforce;
    private final int defaultTtlMinutes;
    private final int maxTtlMinutes;
    private final int maxEventPage;

    public LcmSettings(@Value("${lcm.enforce:true}") boolean enforce,
                       @Value("${lcm.maker-checker.enforce:true}") boolean makerCheckerEnforce,
                       @Value("${lcm.reservation.default-ttl-minutes:30}") int defaultTtlMinutes,
                       @Value("${lcm.reservation.max-ttl-minutes:1440}") int maxTtlMinutes,
                       @Value("${lcm.events.max-page-size:500}") int maxEventPage) {
        this.enforce = enforce;
        this.makerCheckerEnforce = makerCheckerEnforce;
        this.defaultTtlMinutes = defaultTtlMinutes;
        this.maxTtlMinutes = maxTtlMinutes;
        this.maxEventPage = maxEventPage;
    }

    public boolean enforce() { return enforce; }
    public boolean makerCheckerEnforce() { return makerCheckerEnforce; }
    public int defaultTtlMinutes() { return defaultTtlMinutes; }
    public int maxTtlMinutes() { return maxTtlMinutes; }
    public int maxEventPage() { return maxEventPage; }
}
