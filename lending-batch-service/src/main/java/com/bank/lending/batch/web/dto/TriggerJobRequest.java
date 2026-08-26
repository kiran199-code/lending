package com.bank.lending.batch.web.dto;

import java.time.LocalDate;
import java.util.Map;

/**
 * businessDate is optional — omit for "run for today" (the normal ad-hoc case); set it for a
 * backdated/EOD catch-up re-run.
 *
 * <p>jobParams is optional and, when supplied, replaces (not merges with) this job's configured
 * job_params_json for this run only — this is how an event-triggered job (LOAN_CLOSURE_NOC_GENERATION,
 * FORECLOSURE_PREPAYMENT_PROCESSING, WRITE_OFF_PROCESSING, SETTLEMENT_OTS_PROCESSING,
 * LOAN_RESTRUCTURING_BATCH) receives its invocation context, e.g. {@code {"accountId": 12345}},
 * since those jobs have no cron-scheduled run to pick up a static config value from. Readable
 * inside the job's processor/tasklet via {@link com.bank.lending.batch.core.BatchJobContext#jobParamsJson()}.
 */
public record TriggerJobRequest(LocalDate businessDate, Map<String, Object> jobParams) {
}
