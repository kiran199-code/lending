package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: re-derives each account's RBI IRAC asset-classification bucket from its current DPD.
 *
 * <p>TODO: business logic — batch-service-owned equivalent of lending-service's
 * LoanAccountService#classifyDelinquency: read the DPD figure DPD_UPDATE_DAILY persisted for
 * accountId, map it to a bucket (SMA-0/1/2, sub-standard, doubtful, loss) using the thresholds
 * in {@link BatchJobContext#jobParamsJson()} (e.g.
 * {@code {"smaThresholds": [30,60,90], "substandardDays": 365, "doubtfulDays": 1095}}), and
 * update loan_account.account_status when it changes. Must run after DPD_UPDATE_DAILY —
 * batch_job_config.priority should reflect that ordering.
 */
@Component
public class NpaClassificationCheckBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public NpaClassificationCheckBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "NPA_CLASSIFICATION_CHECK_DAILY";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — reclassify accountId's IRAC bucket from its current DPD.
            return accountId;
        };
    }
}
