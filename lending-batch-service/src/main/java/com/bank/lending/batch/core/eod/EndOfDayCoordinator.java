package com.bank.lending.batch.core.eod;

import com.bank.lending.batch.lending.LendingServiceClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs {@link EndOfDayService} in the background (a full EOD can outlast an HTTP request) and keeps
 * the last result for the status endpoint. One run at a time per instance; across instances
 * lending-service refuses a second "start end of day" while one is running, and Spring Batch refuses
 * a second concurrent run of the same job.
 */
@Service
public class EndOfDayCoordinator {

    private static final Logger log = LoggerFactory.getLogger(EndOfDayCoordinator.class);

    private final EndOfDayService endOfDayService;
    private final LendingServiceClient lending;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "end-of-day");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Reads the business date for the status endpoint in the background, so a status call never waits for a sleeping lending-service. */
    private final ExecutorService dateRefresher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "eod-date-refresh");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private volatile LendingServiceClient.BusinessDateInfo cachedDate;
    private volatile Instant cachedAt;
    private static final Duration CACHE_MAX_AGE = Duration.ofMinutes(2);
    private final EodProgress progress = new EodProgress();
    private volatile EodRunSummary lastRun;

    public EndOfDayCoordinator(LendingServiceClient lending, SpringBatchEodJobGateway gateway) {
        this.lending = lending;
        this.endOfDayService = new EndOfDayService(lending, gateway);
    }

    /** Thrown by {@link #trigger(String, LocalDate)} when the caller's business date is not lending-service's current one. */
    public static class StaleBusinessDateException extends RuntimeException {
        public StaleBusinessDateException(String message) {
            super(message);
        }
    }

    /** @return false if a run is already in progress on this instance (nothing started) */
    public boolean trigger(String actor) {
        return trigger(actor, null);
    }

    /**
     * As {@link #trigger(String)}, but when {@code expectedDate} is given the business date is read from lending-service first
     * (fresh, not the status cache) and nothing starts unless it matches: the operator saw that date on a screen.
     *
     * @throws StaleBusinessDateException when the business date is not {@code expectedDate}
     */
    public boolean trigger(String actor, LocalDate expectedDate) {
        if (running.get()) {
            return false;
        }
        if (expectedDate != null) {
            LendingServiceClient.BusinessDateInfo now = lending.businessDate();
            if (!expectedDate.equals(now.date())) {
                throw new StaleBusinessDateException("The business date is now " + now.businessDate() + ", not " + expectedDate
                        + ". Check the screen and try again.");
            }
        }
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        progress.begin(actor);
        executor.submit(() -> {
            try {
                lastRun = endOfDayService.run(actor, progress);
            } catch (RuntimeException e) {
                log.error("End of day crashed", e);
                progress.crashed(e.getMessage());
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    public boolean isRunning() {
        return running.get();
    }

    public EodRunSummary lastRun() {
        return lastRun;
    }

    /** Live progress of the current run, or of the last one; state IDLE if none has run since this instance started. */
    public EodProgress.View progress() {
        return progress.snapshot();
    }

    /**
     * The platform's business date as last read from lending-service, for the status endpoint. Never blocks: it starts a background
     * refresh (one at a time) and returns the last value read within the past two minutes, or null when there is none (lending-service
     * unreachable or still waking up). The next status call, a few seconds later, picks up the refreshed value.
     */
    public LendingServiceClient.BusinessDateInfo businessDate() {
        refreshDateInBackground();
        Instant at = cachedAt;
        return at != null && Duration.between(at, Instant.now()).compareTo(CACHE_MAX_AGE) < 0 ? cachedDate : null;
    }

    private void refreshDateInBackground() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        dateRefresher.submit(() -> {
            try {
                cachedDate = lending.businessDateOnce();
                cachedAt = Instant.now();
            } catch (RuntimeException e) {
                log.warn("Could not read the business date: {}", e.getMessage());
            } finally {
                refreshing.set(false);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        dateRefresher.shutdown();
    }
}
