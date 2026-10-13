package com.bank.lending.batch.core.eod;

import com.bank.lending.batch.lending.LendingServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One end-of-day run for the platform's business date D:
 * <ol>
 *   <li>tell lending-service end of day for D has started (OPEN to EOD_RUNNING; if it is already
 *       EOD_RUNNING this is a resume of an earlier, failed run);</li>
 *   <li>run every enabled DAILY job for D in priority order, skipping any that already completed for D,
 *       and stop at the first one that does not complete;</li>
 *   <li>only if all completed, close D and open D+1.</li>
 * </ol>
 * Because the date moves only after the jobs succeed, a failed night leaves the platform on D, in
 * EOD_RUNNING, with new transactions for D still possible to inspect but no D+1 postings — fix the
 * cause and run it again.
 *
 * <p>Plain class (no Spring annotations) so it can be unit-tested with fakes.
 */
public class EndOfDayService {

    private static final Logger log = LoggerFactory.getLogger(EndOfDayService.class);

    private final LendingServiceClient lending;
    private final EodJobGateway jobs;

    public EndOfDayService(LendingServiceClient lending, EodJobGateway jobs) {
        this.lending = lending;
        this.jobs = jobs;
    }

    public EodRunSummary run(String actor) {
        return run(actor, EodListener.NOOP);
    }

    /** As {@link #run(String)}, reporting each milestone to {@code listener} while the run is in progress. */
    public EodRunSummary run(String actor, EodListener listener) {
        Instant started = Instant.now();
        List<EodJobResult> results = new ArrayList<>();
        LocalDate date = null;
        try {
            LendingServiceClient.BusinessDateInfo info = lending.businessDate();
            boolean resumed = info.eodRunning();
            if (!resumed) {
                info = lending.startEndOfDay(actor);
            } else {
                log.info("Resuming end of day for {}", info.businessDate());
            }
            date = info.date();
            List<String> jobNames = jobs.dailyJobNames();
            listener.started(date, jobNames, resumed);

            for (String jobName : jobNames) {
                if (jobs.alreadyCompleted(jobName, date)) {
                    EodJobResult skipped = new EodJobResult(jobName, "SKIPPED", "already completed for " + date);
                    results.add(skipped);
                    listener.jobFinished(skipped);
                    continue;
                }
                listener.jobStarted(jobName);
                EodJobResult result = jobs.run(jobName, date);
                results.add(result);
                listener.jobFinished(result);
                if (!result.ok()) {
                    return failed(started, date, results,
                            "Job " + jobName + " ended " + result.status() + "; business date " + date
                                    + " stays in EOD_RUNNING. Fix the cause and run end of day again.", listener);
                }
            }

            LendingServiceClient.BusinessDateInfo next = lending.advanceBusinessDate(date, actor);
            log.info("End of day complete: business date {} -> {}", date, next.businessDate());
            EodRunSummary done = new EodRunSummary(date.toString(), started, Instant.now(), EodRunSummary.Outcome.ADVANCED,
                    next.businessDate(), results, null);
            listener.finished(done);
            return done;
        } catch (RuntimeException e) {
            log.error("End of day failed", e);
            return failed(started, date, results, e.getMessage(), listener);
        }
    }

    private static EodRunSummary failed(Instant started, LocalDate date, List<EodJobResult> results, String error,
                                        EodListener listener) {
        log.warn("End of day stopped for {}: {}", date, error);
        EodRunSummary summary = new EodRunSummary(date == null ? null : date.toString(), started, Instant.now(),
                EodRunSummary.Outcome.FAILED, null, results, error);
        listener.finished(summary);
        return summary;
    }
}
