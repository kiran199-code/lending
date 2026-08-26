package com.bank.lending.batch.jobs.monthly;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Monthly: applies a benchmark rate change for MCLR/repo-linked floating-rate products that
 * reset monthly.
 *
 * <p>TODO: business logic — same mechanics as RATE_RESET_CHECK_DAILY (mirroring
 * LoanAccountService#applyFloatingRateReset) but for the monthly-reset benchmark named in
 * {@link BatchJobContext#jobParamsJson()} (e.g. {@code {"benchmarkRateRef": "MCLR_1Y"}}).
 */
@Component
public class RateResetMonthlyBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public RateResetMonthlyBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "RATE_RESET_MONTHLY";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE' AND rate_type = 'FLOATING'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — apply this month's benchmark rate to accountId if it
            // references the monthly-reset benchmark configured in job_params_json.
            return accountId;
        };
    }
}
