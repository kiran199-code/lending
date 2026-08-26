package com.bank.lending.batch.jobs.periodic;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Quarterly (tasklet — one book-wide review, not per-account): board/ALCO-level review of the
 * NPA provisioning coverage built up by the monthly PROVISIONING_CALCULATION_MONTHLY job.
 *
 * <p>TODO: business logic — for the quarter ending at {@link BatchJobContext#businessDate()},
 * aggregate provisioning by NPA sub-category (SMA0/1/2, SUBSTANDARD, DOUBTFUL, LOSS), compare
 * against the RBI/IFRS9 required coverage ratio, and generate the quarterly provisioning
 * adequacy report for review.
 */
@Component
public class QuarterlyNpaProvisioningReviewBatchJob implements BusinessBatchTasklet {

    @Override
    public String getJobName() {
        return "QUARTERLY_NPA_PROVISIONING_REVIEW";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            // TODO: business logic — build the quarterly NPA provisioning adequacy report.
            return RepeatStatus.FINISHED;
        };
    }
}
