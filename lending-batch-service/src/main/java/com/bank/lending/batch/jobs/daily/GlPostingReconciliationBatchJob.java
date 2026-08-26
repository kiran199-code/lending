package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Daily EOD (tasklet — single aggregate operation, not per-account): drains the ledger outbox
 * and reconciles postings against the core GL.
 *
 * <p>TODO: business logic — the batch-owned equivalent/superset of lending-service's
 * LedgerOutboxService#pushPendingEntries: page through ledger_outbox rows with
 * posting_status=PENDING for {@link BatchJobContext#businessDate()}, push each to the real core
 * banking ledger gateway (LedgerGatewayClient today is only a logging stub), then reconcile the
 * day's POSTED total against the core GL trial balance and raise a discrepancy alert if they
 * don't match.
 */
@Component
public class GlPostingReconciliationBatchJob implements BusinessBatchTasklet {

    @Override
    public String getJobName() {
        return "GL_POSTING_RECONCILIATION_DAILY";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            // TODO: business logic — drain ledger_outbox and reconcile against the core GL for
            // BatchJobContext.businessDate().
            return RepeatStatus.FINISHED;
        };
    }
}
