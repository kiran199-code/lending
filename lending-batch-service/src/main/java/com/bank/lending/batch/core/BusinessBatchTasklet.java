package com.bank.lending.batch.core;

import org.springframework.batch.core.step.tasklet.Tasklet;

/**
 * Marker + factory for a single-action, tasklet-style business job — the right shape for a job
 * that does one thing (generate a file, run one aggregate SQL statement/reconciliation, call one
 * external service) rather than iterate a large record set. Chunk-oriented, partitionable jobs
 * implement {@link BusinessChunkJob} instead; both are picked up by {@code BatchJobRegistry} at
 * startup and matched to their {@code batch_job_config} row by {@link #getJobName()}.
 *
 * <p>To add a new tasklet-style job: implement this interface as a {@code @Component}, insert a
 * matching {@code batch_job_config} row with {@code job_type = 'TASKLET'}, and reload config
 * (or restart) — no framework code changes. See README "Adding a new batch job".
 */
public interface BusinessBatchTasklet {

    /** Must exactly match the job_name of this job's row in batch_job_config. */
    String getJobName();

    /**
     * Builds the tasklet that performs this job's work. Implementations receive the run's
     * businessDate and resolved job_params_json (already parsed) via the {@link BatchJobContext}
     * ThreadLocal/step-scoped accessor rather than as method parameters, so the same Tasklet
     * instance can be built once and remain step-scoped for restartability.
     */
    Tasklet buildTasklet();
}
