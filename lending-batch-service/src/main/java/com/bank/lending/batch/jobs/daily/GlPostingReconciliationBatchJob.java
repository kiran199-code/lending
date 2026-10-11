package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import com.bank.lending.batch.core.JobParams;
import com.bank.lending.batch.lending.LendingServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Daily EOD (tasklet — single aggregate operation, not per-account): drains lending-service's ledger
 * outbox towards the core ledger.
 *
 * <p>Repeatedly calls {@code POST /api/v1/ledger-outbox/push?batchSize=} and checks
 * {@code GET /api/v1/ledger-outbox/pending} until nothing is PENDING, for at most
 * {@code job_params_json.maxRounds} rounds (default 50) of {@code batchSize} entries (default 200).
 * If entries are still PENDING afterwards — the ledger gateway keeps rejecting them — the job fails
 * so ops see it in the admin status view, rather than finding out at month-end.
 *
 * <p>Not done yet: reconciling the day's POSTED total against the core GL trial balance. The
 * lending-service ledger gateway is a logging stub today and there is no trial-balance source, so
 * there is nothing real to compare against; add that comparison when the gateway is real.
 */
@Component
public class GlPostingReconciliationBatchJob implements BusinessBatchTasklet {

    private static final Logger log = LoggerFactory.getLogger(GlPostingReconciliationBatchJob.class);
    static final int DEFAULT_BATCH_SIZE = 200;
    static final int DEFAULT_MAX_ROUNDS = 50;

    private final LendingServiceClient lending;

    public GlPostingReconciliationBatchJob(LendingServiceClient lending) {
        this.lending = lending;
    }

    @Override
    public String getJobName() {
        return "GL_POSTING_RECONCILIATION_DAILY";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            JobParams params = JobParams.current();
            int batchSize = params.intValue("batchSize", DEFAULT_BATCH_SIZE);
            int maxRounds = params.intValue("maxRounds", DEFAULT_MAX_ROUNDS);

            int pending = lending.countPendingLedgerEntries(batchSize);
            int rounds = 0;
            while (pending > 0 && rounds < maxRounds) {
                lending.pushLedgerOutbox(batchSize);
                rounds++;
                pending = lending.countPendingLedgerEntries(batchSize);
            }
            if (pending > 0) {
                throw new IllegalStateException("Ledger outbox not drained for " + BatchJobContext.businessDate()
                        + ": at least " + pending + " entries still PENDING after " + rounds + " push round(s)");
            }
            log.info("Ledger outbox drained for {} after {} push round(s)", BatchJobContext.businessDate(), rounds);
            return RepeatStatus.FINISHED;
        };
    }
}
