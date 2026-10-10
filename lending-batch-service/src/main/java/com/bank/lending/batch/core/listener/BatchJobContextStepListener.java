package com.bank.lending.batch.core.listener;

import com.bank.lending.batch.core.JobParamsUtil;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

/**
 * Populates {@link BatchJobContext} (businessDate + job_params_json) on the executing thread
 * before each step runs, and clears it after. Attached to every step BatchJobRegistry assembles
 * — including each partition's worker-step execution, which runs on its own TaskExecutor thread
 * — so a processor/tasklet can read BatchJobContext regardless of which thread it happens to
 * execute on.
 *
 * <p>jobParamsJson is read from this run's {@code JobParameters} (set by
 * BatchJobLauncherService — the batch_job_config default unless the caller supplied a per-launch
 * override, e.g. an accountId for an event-triggered job) rather than fixed at job-assembly time,
 * so the same assembled Job can carry different params on different launches.
 */
public class BatchJobContextStepListener implements StepExecutionListener {

    @Override
    public void beforeStep(StepExecution stepExecution) {
        String jobParamsJson = stepExecution.getJobParameters().getString("jobParamsJson");
        BatchJobContext.set(JobParamsUtil.businessDate(stepExecution.getJobParameters()), jobParamsJson);
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        BatchJobContext.clear();
        return stepExecution.getExitStatus();
    }
}
