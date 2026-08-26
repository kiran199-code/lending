package com.bank.lending.batch.jobs.monthly;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Monthly: rolls accrued-but-unbilled interest into principal for products that capitalize
 * monthly (e.g. moratorium-period loans).
 *
 * <p>TODO: business logic — for eligible accountId (product flag not yet modeled — extend
 * job_params_json or loan_account with a capitalization-eligible marker), sum interest_accrual
 * rows since the last capitalization date, add that total to outstanding principal, and
 * regenerate the amortization schedule for the remaining tenure (same mechanics as
 * lending-service's AmortizationScheduleService#regenerateSchedule).
 */
@Component
public class InterestCapitalizationBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public InterestCapitalizationBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "INTEREST_CAPITALIZATION_MONTHLY";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — capitalize accrued interest into principal for accountId, if eligible.
            return accountId;
        };
    }
}
