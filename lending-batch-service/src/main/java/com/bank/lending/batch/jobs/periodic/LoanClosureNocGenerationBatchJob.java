package com.bank.lending.batch.jobs.periodic;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Event-triggered (tasklet; cron disabled — fired via the manual/API trigger path when an account
 * transitions to CLOSED or FORECLOSED): generates the No-Objection Certificate / loan closure
 * letter and, for mortgage-backed accounts, initiates charge release.
 *
 * <p>TODO: business logic — for the accountId supplied in {@link
 * BatchJobContext#jobParamsJson()} (e.g. {@code {"accountId": 12345}}), verify account_status is
 * CLOSED/FORECLOSED and outstanding balance is zero, generate the NOC/closure letter, and for
 * account_type=MORTGAGE transition the linked mortgage's charge_status toward RELEASED (see
 * MortgageService).
 */
@Component
public class LoanClosureNocGenerationBatchJob implements BusinessBatchTasklet {

    @Override
    public String getJobName() {
        return "LOAN_CLOSURE_NOC_GENERATION";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            // TODO: business logic — generate the NOC/closure letter and initiate mortgage charge release.
            return RepeatStatus.FINISHED;
        };
    }
}
