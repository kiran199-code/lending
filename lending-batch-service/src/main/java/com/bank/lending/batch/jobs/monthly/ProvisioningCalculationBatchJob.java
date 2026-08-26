package com.bank.lending.batch.jobs.monthly;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Monthly: computes regulatory provisioning for every NPA-bucketed account.
 *
 * <p>TODO: business logic — for accountId in any NPA_* status, apply the provisioning percentage
 * configured per IRAC stage in {@link BatchJobContext#jobParamsJson()} (e.g.
 * {@code {"NPA_SMA2": 0.10, "NPA_SUBSTANDARD": 0.15, "NPA_DOUBTFUL": 0.40, "NPA_LOSS": 1.00}})
 * against the account's outstanding principal, and persist the result to a provisioning
 * ledger/table (not yet modeled) for GL posting and REGULATORY_REPORTING_MONTHLY to consume.
 */
@Component
public class ProvisioningCalculationBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public ProvisioningCalculationBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "PROVISIONING_CALCULATION_MONTHLY";
    }

    @Override
    protected String accountFilter() {
        return "account_status IN ('NPA_SMA0','NPA_SMA1','NPA_SMA2','NPA_SUBSTANDARD','NPA_DOUBTFUL','NPA_LOSS')";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — compute and persist provisioning for NPA accountId.
            return accountId;
        };
    }
}
