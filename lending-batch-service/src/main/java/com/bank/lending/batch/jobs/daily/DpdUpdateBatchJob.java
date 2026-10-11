package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.lending.LendingServiceClient;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: recomputes days-past-due for every performing or delinquent account.
 *
 * <p>Calls {@code POST /api/v1/loan-accounts/{id}/delinquency-classification} — lending-service finds
 * the oldest unpaid instalment, computes the DPD as of the business date, flags the instalment
 * OVERDUE and moves the account between ACTIVE / SMA / NPA statuses. The call is idempotent for a
 * given (account, date). Items whose status did not change are filtered, so the step's "write count"
 * is the number of accounts that moved bucket.
 *
 * <p>Note: lending-service does not persist DPD itself yet, and its bucketing is a single
 * endpoint, so NPA_CLASSIFICATION_CHECK_DAILY currently calls the same endpoint (a cheap,
 * idempotent re-check). When DPD is persisted and the thresholds become configurable, the two jobs
 * will diverge as originally intended.
 */
@Component
public class DpdUpdateBatchJob extends AbstractAccountRangeChunkJob<Long> {

    private final LendingServiceClient lending;

    public DpdUpdateBatchJob(DataSource dataSource, LendingServiceClient lending) {
        super(dataSource);
        this.lending = lending;
    }

    @Override
    public String getJobName() {
        return "DPD_UPDATE_DAILY";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            LendingServiceClient.DelinquencyResult result =
                    lending.classifyDelinquency(accountId, BatchJobContext.businessDate());
            return result.statusChanged() ? accountId : null;
        };
    }
}
