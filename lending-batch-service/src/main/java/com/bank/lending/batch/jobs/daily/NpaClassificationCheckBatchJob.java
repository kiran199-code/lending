package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.lending.LendingServiceClient;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: re-derives each account's SMA / NPA (IRAC) asset-classification bucket from its DPD.
 *
 * <p>Calls {@code POST /api/v1/loan-accounts/{id}/delinquency-classification}, the same idempotent
 * endpoint DPD_UPDATE_DAILY uses (see the note there): by the time this job runs (priority after
 * the DPD job) it normally finds nothing to change and acts as a verification pass. Items whose
 * status did not change are filtered. Per-run thresholds in {@code job_params_json} are not used
 * yet — lending-service applies fixed bands until its thresholds become configurable.
 */
@Component
public class NpaClassificationCheckBatchJob extends AbstractAccountRangeChunkJob<Long> {

    private final LendingServiceClient lending;

    public NpaClassificationCheckBatchJob(DataSource dataSource, LendingServiceClient lending) {
        super(dataSource);
        this.lending = lending;
    }

    @Override
    public String getJobName() {
        return "NPA_CLASSIFICATION_CHECK_DAILY";
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
