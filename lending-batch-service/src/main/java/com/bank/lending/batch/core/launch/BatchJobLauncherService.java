package com.bank.lending.batch.core.launch;

import com.bank.lending.batch.core.BatchJobRegistry;
import com.bank.lending.batch.domain.BatchJobConfig;
import com.bank.lending.batch.repository.BatchJobConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * The single entry point every trigger path (Quartz, ad-hoc REST, backdated re-run) goes
 * through to actually start a job — the idempotency guard and businessDate/restart parameter
 * rules live here exactly once, rather than being duplicated in QuartzJobBridge and
 * BatchAdminController.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchJobLauncherService {

    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final BatchJobRegistry batchJobRegistry;
    private final BatchJobConfigRepository batchJobConfigRepository;

    /** Quartz-triggered run: businessDate defaults to today (the normal EOD case), job_params_json is the configured default. */
    public JobExecution launchScheduled(String jobName) throws Exception {
        return launch(jobName, LocalDate.now(), "SCHEDULED", null);
    }

    /** Ad-hoc/backdated run from the admin API — businessDate is caller-supplied (defaults to today if null), using the configured default job_params_json. */
    public JobExecution launchManual(String jobName, LocalDate businessDate) throws Exception {
        return launch(jobName, businessDate != null ? businessDate : LocalDate.now(), "MANUAL", null);
    }

    /**
     * Ad-hoc/backdated run with a per-launch job_params_json override — the path event-triggered
     * jobs use to receive their invocation context (e.g. {@code {"accountId": 12345}}) since they
     * have no cron schedule to pick up a static config value from. jobParamsOverride replaces
     * (not merges with) the batch_job_config default for this run only; pass null to fall back to
     * the configured default.
     */
    public JobExecution launchManual(String jobName, LocalDate businessDate, String jobParamsOverride) throws Exception {
        return launch(jobName, businessDate != null ? businessDate : LocalDate.now(), "MANUAL", jobParamsOverride);
    }

    private JobExecution launch(String jobName, LocalDate businessDate, String triggeredBy, String jobParamsOverride) throws Exception {
        BatchJobConfig config = batchJobConfigRepository.findByJobName(jobName)
                .orElseThrow(() -> new IllegalArgumentException("No batch_job_config row for job " + jobName));
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw new IllegalStateException("Job " + jobName + " is disabled — enable it in batch_job_config first");
        }

        // Idempotency guard: refuse to start a second concurrent run of the same job_name.
        Set<JobExecution> running = jobExplorer.findRunningJobExecutions(jobName);
        if (!running.isEmpty()) {
            throw new IllegalStateException("Job " + jobName + " already has a RUNNING execution (id="
                    + running.iterator().next().getId() + ") — refusing to start a duplicate run");
        }

        String effectiveJobParamsJson = jobParamsOverride != null
                ? jobParamsOverride
                : (config.getJobParamsJson() != null ? config.getJobParamsJson() : "{}");

        Job job = batchJobRegistry.getJob(jobName);
        // Only businessDate (and, for non-restartable jobs, runId) identify the JobInstance —
        // triggeredBy and jobParamsJson ride along as non-identifying parameters so a MANUAL
        // retry of a SCHEDULED run (or a retry with a param override) still resumes the same
        // JobInstance for a restartable job, per the businessDate-only restart contract described
        // in the README.
        JobParametersBuilder params = new JobParametersBuilder()
                .addString("businessDate", businessDate.toString())
                .addString("triggeredBy", triggeredBy, false)
                .addString("jobParamsJson", effectiveJobParamsJson, false);

        // restartable=false jobs get a unique nonce so a re-run always starts a fresh JobInstance
        // instead of Spring Batch trying to resume a (by design, non-resumable) prior attempt.
        if (!Boolean.TRUE.equals(config.getRestartable())) {
            params.addString("runId", UUID.randomUUID().toString());
        }

        JobParameters jobParameters = params.toJobParameters();
        log.info("Launching job={} businessDate={} triggeredBy={} restartable={} paramsOverridden={}",
                jobName, businessDate, triggeredBy, config.getRestartable(), jobParamsOverride != null);
        return jobLauncher.run(job, jobParameters);
    }
}
