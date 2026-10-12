package com.bank.lending.batch.core.eod;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of one end-of-day run. ADVANCED: every daily job completed and the business date moved on.
 * FAILED: something stopped the run; the business date stays in EOD_RUNNING and a re-run resumes from
 * the failed job (finished jobs are not repeated).
 */
public record EodRunSummary(
        String businessDate,
        Instant startedAt,
        Instant finishedAt,
        Outcome outcome,
        String newBusinessDate,
        List<EodJobResult> jobs,
        String error
) {
    public enum Outcome { ADVANCED, FAILED }
}
