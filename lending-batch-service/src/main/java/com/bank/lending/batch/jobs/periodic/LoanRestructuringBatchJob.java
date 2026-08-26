package com.bank.lending.batch.jobs.periodic;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Event-triggered (tasklet; cron disabled — fired via the manual/API trigger path once a
 * restructuring proposal, e.g. tenure extension, moratorium, or rate concession, is approved):
 * applies the approved restructuring terms to the account.
 *
 * <p>TODO: business logic — for the accountId/restructuring terms supplied in {@link
 * BatchJobContext#jobParamsJson()}, apply the new tenure/rate/moratorium terms to loan_account,
 * regenerate the amortization schedule (bumping schedule_version, same mechanics as
 * AmortizationScheduleService#regenerateSchedule), and enroll the account in
 * RESTRUCTURED_LOAN_MONITORING_MONTHLY going forward.
 */
@Component
public class LoanRestructuringBatchJob implements BusinessBatchTasklet {

    @Override
    public String getJobName() {
        return "LOAN_RESTRUCTURING_BATCH";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            // TODO: business logic — apply approved restructuring terms and regenerate the amortization schedule.
            return RepeatStatus.FINISHED;
        };
    }
}
