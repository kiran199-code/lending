package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import com.bank.lending.batch.lending.LendingServiceClient;
import com.bank.lending.batch.lending.LendingServiceClient.ReconciliationCheck;
import com.bank.lending.batch.lending.LendingServiceClient.ReconciliationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Daily EOD (tasklet — one book-wide check): proves the general ledger agrees with the loan sub-ledger before the
 * business date moves on.
 *
 * <p>Calls {@code POST /api/v1/accounting/reconciliation/run?businessDate=}. lending-service checks that every ledger
 * balances, that no posting exception that gates end of day is still open, and, account by account, that the ledger's
 * principal (not due and due), accrued and due interest, penal interest and advance equal what the demand, accrual and
 * account tables say. The result is stored (see the Reconciliation screen).
 *
 * <p>If anything differs the job fails with the failed checks in its message, which stops end of day on this date, so
 * the books are never rolled forward over a break. Fix the cause (retry or waive the posting exception, correct the
 * route or template) and run end of day again; the check is repeatable. When accounting is switched off in
 * lending-service the run still succeeds with nothing to compare.
 *
 * <p>Runs after every job that can post a journal (the last is CHARGES_FEE_APPLICATION_DAILY at priority 100) and
 * before the balance snapshot.
 */
@Component
public class AccountingReconciliationBatchJob implements BusinessBatchTasklet {

    private static final Logger log = LoggerFactory.getLogger(AccountingReconciliationBatchJob.class);

    private final LendingServiceClient lending;

    public AccountingReconciliationBatchJob(LendingServiceClient lending) {
        this.lending = lending;
    }

    @Override
    public String getJobName() {
        return "ACCOUNTING_RECONCILIATION_DAILY";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            ReconciliationOutcome outcome = lending.runAccountingReconciliation(BatchJobContext.businessDate());
            if (!outcome.allPassed()) {
                String failed = outcome.failed().stream().map(AccountingReconciliationBatchJob::describe)
                        .collect(Collectors.joining("; "));
                throw new IllegalStateException("Accounting reconciliation failed for " + BatchJobContext.businessDate()
                        + (failed.isEmpty() ? ": no checks were returned" : ": " + failed));
            }
            log.info("Accounting reconciliation passed for {} ({} checks)", BatchJobContext.businessDate(),
                    outcome.checks().size());
            return RepeatStatus.FINISHED;
        };
    }

    private static String describe(ReconciliationCheck c) {
        return c.checkCode() + " (" + (c.detail() == null || c.detail().isBlank() ? "no detail" : c.detail()) + ")";
    }
}
