package com.bank.lending.batch.jobs.monthly;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Monthly: flags accounts with no recent transaction activity for review.
 *
 * <p>TODO: business logic — for accountId, find the most recent emi_transaction.value_date; if
 * {@link BatchJobContext#businessDate()} minus that date exceeds
 * {@code job_params_json.dormancyThresholdDays} (default 180), flag the account for a
 * dormancy/closure review workflow.
 */
@Component
public class DormantInactiveAccountReviewBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public DormantInactiveAccountReviewBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "DORMANT_INACTIVE_ACCOUNT_REVIEW_MONTHLY";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — check accountId's last activity date against the dormancy threshold.
            return accountId;
        };
    }
}
