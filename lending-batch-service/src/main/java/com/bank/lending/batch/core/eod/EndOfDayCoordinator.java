package com.bank.lending.batch.core.eod;

import com.bank.lending.batch.lending.LendingServiceClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
    private final EodProgress progress = new EodProgress();
    private volatile EodRunSummary lastRun;

    public EndOfDayCoordinator(LendingServiceClient lending, SpringBatchEodJobGateway gateway) {
        this.lending = lending;
        this.endOfDayService = new EndOfDayService(lending, gateway);
    }

    /** @return false if a run is already in progress on this instance (nothing started) */
    public boolean trigger(String actor) {
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

    /** The platform's business date as lending-service reports it; null if lending-service cannot be reached. */
    public LendingServiceClient.BusinessDateInfo businessDate() {
        try {
            return lending.businessDate();
        } catch (RuntimeException e) {
            log.warn("Could not read the business date: {}", e.getMessage());
            return null;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
