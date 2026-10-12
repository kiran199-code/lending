package com.bank.lending.batch.core.eod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Starts the nightly end-of-day run. Replaces the individual cron triggers of the DAILY jobs (those
 * are ignored while {@code batch.eod.enabled=true}). Several instances may fire at once; only one wins
 * because lending-service allows a single "start end of day".
 */
@Component
@ConditionalOnProperty(name = "batch.eod.enabled", havingValue = "true", matchIfMissing = true)
public class EndOfDayScheduler {

    private static final Logger log = LoggerFactory.getLogger(EndOfDayScheduler.class);

    private final EndOfDayCoordinator coordinator;

    public EndOfDayScheduler(EndOfDayCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(cron = "${batch.eod.cron:0 0 1 * * ?}", zone = "${batch.eod.zone:Asia/Kolkata}")
    public void nightly() {
        if (!coordinator.trigger("scheduler")) {
            log.info("Scheduled end of day skipped: a run is already in progress");
        }
    }
}
