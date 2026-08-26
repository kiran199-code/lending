package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Daily EOD (tasklet — a single book-wide balancing check, not per-account): verifies the day's
 * opening balance plus all posted movements reconcile to the closing balance.
 *
 * <p>TODO: business logic — for {@link BatchJobContext#businessDate()}, sum all movements posted
 * across the book (disbursements, EMI postings, part-payments, charges, foreclosures — every
 * emi_transaction row with value_date = businessDate, plus DISBURSEMENT ledger_outbox entries)
 * and confirm opening_balance + movements = closing_balance. On a mismatch, fail this step with a
 * descriptive exit status so ops sees it immediately in BatchAdminController's status view rather
 * than discovering it during month-end close.
 */
@Component
public class SodEodBalancingBatchJob implements BusinessBatchTasklet {

    @Override
    public String getJobName() {
        return "SOD_EOD_BALANCING_DAILY";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            // TODO: business logic — reconcile opening + movements = closing balance for the day.
            return RepeatStatus.FINISHED;
        };
    }
}
