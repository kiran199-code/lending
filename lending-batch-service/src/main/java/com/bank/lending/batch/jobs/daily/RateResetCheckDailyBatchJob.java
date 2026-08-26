package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: applies a benchmark rate change for floating-rate products whose benchmark resets
 * daily (e.g. an overnight/T-bill-linked product).
 *
 * <p>TODO: business logic — batch-service-owned equivalent of lending-service's
 * LoanAccountService#applyFloatingRateReset: read today's published rate for the benchmark named
 * in {@link BatchJobContext#jobParamsJson()} (e.g. {@code {"benchmarkRateRef": "OVERNIGHT_MIBOR"}})
 * from its rate-source feed (not yet modeled), and for accountId — if it is ACTIVE, FLOATING,
 * and referencing that benchmark — recompute interest_rate = newBenchmarkRate + spread and
 * regenerate the remaining amortization schedule when the rate actually changed.
 */
@Component
public class RateResetCheckDailyBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public RateResetCheckDailyBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "RATE_RESET_CHECK_DAILY";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE' AND rate_type = 'FLOATING'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — apply today's benchmark rate to accountId if it references
            // the daily-reset benchmark configured in job_params_json.
            return accountId;
        };
    }
}
