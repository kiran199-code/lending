package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: computes and posts today's interest accrual for every active account.
 *
 * <p>TODO: business logic — for each account_id in this partition, as of
 * {@link BatchJobContext#businessDate()}: determine current outstanding principal from
 * amortization_schedule (the pending/overdue installment's outstanding_balance + principal_component,
 * same derivation as lending-service's InterestAccrualService#currentOutstandingPrincipal), compute
 * daily interest using loan_account.day_count_convention (ACT/365, ACT/ACT, 30/360 — mirror
 * DayCountCalculator), and upsert the result into interest_accrual idempotently on
 * (account_id, business_date). This is the batch-service-owned equivalent of
 * lending-service's InterestAccrualService#runDailyAccrual; once this job is live, that
 * synchronous path should be considered EOD-batch-only trigger surface, not a second source of
 * accrual writes.
 */
@Component
public class InterestAccrualBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public InterestAccrualBatchJob(DataSource dataSource) {
        super(dataSource);
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
            // TODO: business logic — compute and upsert today's interest_accrual row for accountId.
            return accountId;
        };
    }
}
