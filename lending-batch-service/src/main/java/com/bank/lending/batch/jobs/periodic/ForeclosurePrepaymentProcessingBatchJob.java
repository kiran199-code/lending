package com.bank.lending.batch.jobs.periodic;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Event-triggered (tasklet; cron disabled — fired via the manual/API trigger path when a
 * foreclosure/full-prepayment request is initiated): the batch-owned settlement leg of a
 * foreclosure, complementing the synchronous EmiPaymentService#processForeclosure call.
 *
 * <p>TODO: business logic — for the accountId/foreclosure request supplied in {@link
 * BatchJobContext#jobParamsJson()}, recompute the foreclosure payoff quote as of {@link
 * BatchJobContext#businessDate()} (outstanding principal + accrued interest + foreclosure
 * charges per emi_transaction.transaction_type=FORECLOSURE), post the settlement, and transition
 * account_status to CLOSED.
 */
@Component
public class ForeclosurePrepaymentProcessingBatchJob implements BusinessBatchTasklet {

    @Override
    public String getJobName() {
        return "FORECLOSURE_PREPAYMENT_PROCESSING";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            // TODO: business logic — recompute payoff quote, post foreclosure settlement, close account.
            return RepeatStatus.FINISHED;
        };
    }
}
