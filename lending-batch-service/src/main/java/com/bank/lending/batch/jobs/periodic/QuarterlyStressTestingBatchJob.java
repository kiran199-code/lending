package com.bank.lending.batch.jobs.periodic;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Quarterly (tasklet — one portfolio-wide simulation, not per-account): runs the loan portfolio
 * through stress scenarios (rate shocks, unemployment/default-rate shocks) for capital adequacy
 * planning.
 *
 * <p>TODO: business logic — as of {@link BatchJobContext#businessDate()}, pull the full
 * loan_account book, apply the shock scenarios configured in {@link
 * BatchJobContext#jobParamsJson()} (e.g. {@code {"rateShockBps": 200, "defaultRateMultiplier":
 * 1.5}}) to projected cash flows and provisioning, and publish the stressed capital/loss
 * projection.
 */
@Component
public class QuarterlyStressTestingBatchJob implements BusinessBatchTasklet {

    @Override
    public String getJobName() {
        return "QUARTERLY_STRESS_TESTING";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            // TODO: business logic — run configured stress scenarios across the portfolio.
            return RepeatStatus.FINISHED;
        };
    }
}
