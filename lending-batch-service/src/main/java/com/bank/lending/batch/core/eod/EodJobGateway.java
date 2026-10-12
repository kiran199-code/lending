package com.bank.lending.batch.core.eod;

import java.time.LocalDate;
import java.util.List;

/** What the end-of-day run needs from the batch framework; a seam so the run logic is testable without Spring Batch. */
public interface EodJobGateway {

    /** Enabled DAILY jobs, in the order they must run (batch_job_config.priority ascending). */
    List<String> dailyJobNames();

    /** True if the job already COMPLETED for this business date (a resumed EOD does not repeat finished work). */
    boolean alreadyCompleted(String jobName, LocalDate businessDate);

    /** Runs the job for the business date and waits for it to finish. Never throws: failures come back as a non-COMPLETED status. */
    EodJobResult run(String jobName, LocalDate businessDate);
}
