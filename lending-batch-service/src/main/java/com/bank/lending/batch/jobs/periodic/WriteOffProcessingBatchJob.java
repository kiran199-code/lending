package com.bank.lending.batch.jobs.periodic;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Event-triggered (tasklet; cron disabled — fired via the manual/API trigger path once a
 * write-off decision is approved, typically for accounts in NPA_LOSS): processes the accounting
 * and status transition for a loan write-off.
 *
 * <p>TODO: business logic — for the accountId supplied in {@link
 * BatchJobContext#jobParamsJson()}, verify NPA_LOSS classification and write-off approval,
 * reverse/utilize the associated provisioning, post the write-off entries to ledger_outbox, and
 * transition account_status to WRITTEN_OFF.
 */
@Component
public class WriteOffProcessingBatchJob implements BusinessBatchTasklet {

    @Override
    public String getJobName() {
        return "WRITE_OFF_PROCESSING";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            // TODO: business logic — post write-off entries and transition account to WRITTEN_OFF.
            return RepeatStatus.FINISHED;
        };
    }
}
