package com.bank.lending.batch.core.eod;

import java.time.LocalDate;
import java.util.List;

/**
 * Receives the milestones of one end-of-day run as they happen, so a screen can show live progress.
 * Callbacks come from the end-of-day thread; implementations must not throw.
 */
public interface EodListener {

    EodListener NOOP = new EodListener() { };

    /** The business date is known and the jobs to run are decided. {@code resumed} is true when an earlier run had left the date in EOD_RUNNING. */
    default void started(LocalDate businessDate, List<String> jobNames, boolean resumed) { }

    /** A job is about to run (not called for jobs skipped because they already completed). */
    default void jobStarted(String jobName) { }

    /** A job finished, or was skipped because it had already completed for the date. */
    default void jobFinished(EodJobResult result) { }

    /** The run is over: ADVANCED or FAILED. */
    default void finished(EodRunSummary summary) { }
}
