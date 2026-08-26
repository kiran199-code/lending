package com.bank.lending.batch.jobs.periodic;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Annual: checks property/mortgage insurance policy expiry for mortgage-backed accounts.
 *
 * <p>TODO: business logic — for accountId's linked mortgage (mortgage.insurance_policy_ref /
 * insurance_expiry_date, if not yet modeled these columns should be added), if the policy expires
 * within {@code job_params_json.renewalWindowDays} (default 60) of {@link
 * BatchJobContext#businessDate()}, trigger a renewal reminder / force-placed-insurance workflow.
 */
@Component
public class AnnualInsuranceRenewalCheckBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public AnnualInsuranceRenewalCheckBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "ANNUAL_INSURANCE_RENEWAL_CHECK";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE' AND account_type = 'MORTGAGE'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — check accountId's mortgage insurance expiry and trigger renewal reminder if due.
            return accountId;
        };
    }
}
