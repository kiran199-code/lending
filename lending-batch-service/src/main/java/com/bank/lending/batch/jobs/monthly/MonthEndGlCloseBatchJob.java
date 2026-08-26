package com.bank.lending.batch.jobs.monthly;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Monthly (tasklet — one book-wide close operation, not per-account): runs the month-end GL
 * close checklist.
 *
 * <p>TODO: business logic — for the month ending at {@link BatchJobContext#businessDate()},
 * confirm every ledger_outbox entry for the period reached posting_status=POSTED (fail/alert on
 * any still PENDING/FAILED), reconcile subledger totals (EMI postings, disbursements, charges,
 * foreclosures) to the GL trial balance, then lock the period against further posting — the
 * monthly superset of the daily SOD_EOD_BALANCING_DAILY check.
 */
@Component
public class MonthEndGlCloseBatchJob implements BusinessBatchTasklet {

    @Override
    public String getJobName() {
        return "MONTH_END_GL_CLOSE_MONTHLY";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            // TODO: business logic — run the month-end GL close/reconciliation checklist.
            return RepeatStatus.FINISHED;
        };
    }
}
