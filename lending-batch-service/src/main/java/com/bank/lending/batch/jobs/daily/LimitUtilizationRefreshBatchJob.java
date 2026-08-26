package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: refreshes available-limit figures for revolving (OD/CC-style) products.
 *
 * <p>TODO: business logic — loan_account.account_type currently models only UNSECURED and
 * MORTGAGE (see V1 migration); a revolving OD/CC product would need a new account_type plus a
 * running-utilization concept this schema doesn't have yet. Once modeled: for each revolving
 * accountId, recompute available limit = sanctioned_amount − current utilized balance as of
 * {@link BatchJobContext#businessDate()} and persist it for the day.
 */
@Component
public class LimitUtilizationRefreshBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public LimitUtilizationRefreshBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "LIMIT_UTILIZATION_REFRESH_DAILY";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — recompute available limit for revolving accountId.
            return accountId;
        };
    }
}
