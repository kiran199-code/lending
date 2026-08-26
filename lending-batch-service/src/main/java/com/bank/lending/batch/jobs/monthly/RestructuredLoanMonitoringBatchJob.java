package com.bank.lending.batch.jobs.monthly;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Monthly: verifies accounts under an active restructuring package are honoring the restructured
 * schedule.
 *
 * <p>TODO: business logic — restructuring status isn't yet modeled on loan_account (would need
 * a flag/table set by LOAN_RESTRUCTURING_BATCH on approval); once available, for each restructured
 * accountId still within its monitoring period, check for any fresh delinquency since
 * restructuring and flag a breach for re-classification (typically the concession is withdrawn
 * and the account reverts to standard NPA tracking).
 */
@Component
public class RestructuredLoanMonitoringBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public RestructuredLoanMonitoringBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "RESTRUCTURED_LOAN_MONITORING_MONTHLY";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — check restructuring-monitoring compliance for accountId.
            return accountId;
        };
    }
}
