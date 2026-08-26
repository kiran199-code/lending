package com.bank.lending.batch.jobs.periodic;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Event-triggered (tasklet; cron disabled — fired via the manual/API trigger path once a
 * one-time-settlement (OTS) offer is accepted by the customer): processes a negotiated
 * less-than-full settlement of an NPA account.
 *
 * <p>TODO: business logic — for the accountId/settlement terms supplied in {@link
 * BatchJobContext#jobParamsJson()} (e.g. {@code {"accountId": 12345, "settlementAmount":
 * 450000.00}}), post the settlement receipt, waive the residual balance, and transition
 * account_status to CLOSED with a settlement flag/reason recorded.
 */
@Component
public class SettlementOtsProcessingBatchJob implements BusinessBatchTasklet {

    @Override
    public String getJobName() {
        return "SETTLEMENT_OTS_PROCESSING";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            // TODO: business logic — post OTS settlement receipt, waive residual balance, close account.
            return RepeatStatus.FINISHED;
        };
    }
}
