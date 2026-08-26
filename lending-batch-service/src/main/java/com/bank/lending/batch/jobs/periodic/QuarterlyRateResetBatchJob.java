package com.bank.lending.batch.jobs.periodic;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Quarterly: applies a benchmark rate change for floating-rate products that reset on a
 * quarterly cycle (e.g. T-bill-linked products).
 *
 * <p>TODO: business logic — same mechanics as RATE_RESET_CHECK_DAILY / RATE_RESET_MONTHLY
 * (mirroring LoanAccountService#applyFloatingRateReset) but for the quarterly-reset benchmark
 * named in {@link BatchJobContext#jobParamsJson()} (e.g. {@code {"benchmarkRateRef":
 * "TBILL_91D"}}).
 */
@Component
public class QuarterlyRateResetBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public QuarterlyRateResetBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "QUARTERLY_RATE_RESET";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE' AND rate_type = 'FLOATING'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — apply this quarter's benchmark rate to accountId if it
            // references the quarterly-reset benchmark configured in job_params_json.
            return accountId;
        };
    }
}
