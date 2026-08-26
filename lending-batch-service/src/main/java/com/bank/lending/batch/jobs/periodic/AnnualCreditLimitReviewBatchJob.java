package com.bank.lending.batch.jobs.periodic;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Annual: reviews sanctioned-limit adequacy/renewal for revolving or reviewable-limit facilities.
 *
 * <p>TODO: business logic — for accountId, re-evaluate credit limit against current repayment
 * behavior, updated income/collateral data, and bureau score, and flag for limit
 * enhancement/reduction/renewal decisioning. Complements the daily LIMIT_UTILIZATION_REFRESH_DAILY
 * job, which tracks day-to-day utilization rather than annual limit adequacy.
 */
@Component
public class AnnualCreditLimitReviewBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public AnnualCreditLimitReviewBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "ANNUAL_CREDIT_LIMIT_REVIEW";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — re-evaluate accountId's sanctioned limit for renewal/adjustment.
            return accountId;
        };
    }
}
