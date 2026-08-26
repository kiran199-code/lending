package com.bank.lending.batch.jobs.monthly;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Monthly (tasklet — one file-generation action, not per-account): produces the CIBIL/Experian
 * bureau reporting extract.
 *
 * <p>TODO: business logic — as of {@link BatchJobContext#businessDate()}, extract (account
 * status, DPD, outstanding balance) for every active/NPA account in the bureau's required
 * fixed-width or CSV layout, write the file, and hand it off to the bureau submission channel
 * (SFTP/API — not yet modeled). {@link BatchJobContext#jobParamsJson()} carries the target
 * bureau code, e.g. {@code {"bureau": "CIBIL"}}.
 */
@Component
public class CreditBureauReportingExtractBatchJob implements BusinessBatchTasklet {

    @Override
    public String getJobName() {
        return "CREDIT_BUREAU_REPORTING_EXTRACT_MONTHLY";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            // TODO: business logic — generate and submit the bureau extract file for the month.
            return RepeatStatus.FINISHED;
        };
    }
}
