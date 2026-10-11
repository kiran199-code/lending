package com.bank.lending.batch.jobs.monthly;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import com.bank.lending.batch.lending.LendingServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Monthly (tasklet — one book-wide close operation, not per-account): the month-end gate on the
 * ledger outbox.
 *
 * <p>Calls {@code GET /api/v1/ledger-outbox/pending} and fails the job if any entry is still
 * PENDING, so the books are not closed over unposted ledger entries. It only checks — it never
 * pushes; run GL_POSTING_RECONCILIATION_DAILY first.
 *
 * <p>Not done yet (no lending-service support): detecting entries that exhausted their retries
 * (status FAILED is not exposed by the API), reconciling sub-ledger totals to the GL trial
 * balance, and locking the period against further posting. Until those exist this job is a partial
 * close check, not a full close.
 */
@Component
public class MonthEndGlCloseBatchJob implements BusinessBatchTasklet {

    private static final Logger log = LoggerFactory.getLogger(MonthEndGlCloseBatchJob.class);

    private final LendingServiceClient lending;

    public MonthEndGlCloseBatchJob(LendingServiceClient lending) {
        this.lending = lending;
    }

    @Override
    public String getJobName() {
        return "MONTH_END_GL_CLOSE_MONTHLY";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            int pending = lending.countPendingLedgerEntries(1);
            if (pending > 0) {
                throw new IllegalStateException("Month-end close blocked for period ending "
                        + BatchJobContext.businessDate() + ": ledger outbox still has PENDING entries");
            }
            log.info("Month-end ledger gate passed for period ending {}: no PENDING outbox entries",
                    BatchJobContext.businessDate());
            return RepeatStatus.FINISHED;
        };
    }
}
