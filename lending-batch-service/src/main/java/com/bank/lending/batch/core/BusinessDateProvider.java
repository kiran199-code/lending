package com.bank.lending.batch.core;

import com.bank.lending.batch.lending.LendingServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Where the batch service gets "today" from. By default that is lending-service's business date
 * (source {@code LENDING}), so jobs run for the business date and not for the calendar date.
 * Source {@code SYSTEM} uses the wall clock and exists only for running the batch service on its
 * own with no lending-service (never in a shared environment).
 */
@Component
public class BusinessDateProvider {

    private final LendingServiceClient lending;
    private final boolean useSystemClock;
    private final ZoneId systemZone;

    public BusinessDateProvider(LendingServiceClient lending,
                                @Value("${batch.business-date.source:LENDING}") String source,
                                @Value("${batch.business-date.system-timezone:Asia/Kolkata}") String systemTimezone) {
        this.lending = lending;
        this.useSystemClock = "SYSTEM".equalsIgnoreCase(source);
        this.systemZone = ZoneId.of(systemTimezone);
    }

    public LocalDate current() {
        return useSystemClock ? LocalDate.now(systemZone) : lending.businessDate().date();
    }
}
