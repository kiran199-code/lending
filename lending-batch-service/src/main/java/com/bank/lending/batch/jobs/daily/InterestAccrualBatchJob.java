package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.lending.LendingServiceClient;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: accrues today's interest for every ACTIVE account, one lending-service call per account.
 *
 * <p>Calls {@code POST /api/v1/loan-accounts/{id}/interest-accruals?businessDate=} — lending-service
 * owns the maths (outstanding principal, day-count convention) and writes the accrual idempotently
 * on (account, business date), so a re-run or a retried chunk never double-accrues. Items that
 * did not result in a new accrual (already posted, nothing outstanding) are filtered, so the step's
 * "write count" is the number of accruals actually posted.
 */
@Component
public class InterestAccrualBatchJob extends AbstractAccountRangeChunkJob<Long> {

    private final LendingServiceClient lending;

    public InterestAccrualBatchJob(DataSource dataSource, LendingServiceClient lending) {
        super(dataSource);
        this.lending = lending;
    }

    @Override
    public String getJobName() {
        return "INTEREST_ACCRUAL_DAILY";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            LendingServiceClient.AccrualResult result = lending.accrueInterest(accountId, BatchJobContext.businessDate());
            return result.posted() ? accountId : null;
        };
    }
}
