package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import com.bank.lending.batch.lending.LendingServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Daily EOD (tasklet — one book-wide write): records the day's opening, debit, credit and closing balance of every
 * general-ledger account.
 *
 * <p>Calls {@code POST /api/v1/accounting/snapshot?businessDate=}. lending-service rewrites that date's rows, so a re-run
 * or a retried step leaves the same result. The snapshot is what the trial balance history and, in a later phase, the
 * delivery to the external ledger read from.
 *
 * <p>Runs last in the daily chain, after ACCOUNTING_RECONCILIATION_DAILY has proved the day's journals.
 */
@Component
public class AccountingBalanceSnapshotBatchJob implements BusinessBatchTasklet {

    private static final Logger log = LoggerFactory.getLogger(AccountingBalanceSnapshotBatchJob.class);

    private final LendingServiceClient lending;

    public AccountingBalanceSnapshotBatchJob(LendingServiceClient lending) {
        this.lending = lending;
    }

    @Override
    public String getJobName() {
        return "ACCOUNTING_BALANCE_SNAPSHOT_DAILY";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            int rows = lending.snapshotAccountingBalances(BatchJobContext.businessDate());
            log.info("Accounting balance snapshot for {}: {} account balance(s) written", BatchJobContext.businessDate(), rows);
            return RepeatStatus.FINISHED;
        };
    }
}
