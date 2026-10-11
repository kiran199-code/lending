package com.bank.lending.batch.jobs.monthly;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.JobParams;
import com.bank.lending.batch.lending.LendingServiceClient;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Monthly: applies the published benchmark rate to ACTIVE floating-rate accounts that reference the
 * benchmark named in {@code job_params_json.benchmarkRateRef} (e.g. {@code {"benchmarkRateRef": "MCLR_1Y"}}).
 *
 * <p>Calls {@code POST /api/v1/loan-accounts/{id}/rate-reset}. lending-service looks up the rate in
 * force on the business date in its {@code benchmark_rate} table (publish rates with
 * {@code PUT /api/v1/benchmark-rates/{ref}/{effectiveDate}}), adds the account's spread, applies the
 * product's rate floor/cap, and regenerates the remaining schedule only if the rate actually changed.
 * Each account is reset individually; no event reprices a whole product. Items that were not reset
 * (already current, other benchmark, not eligible) are filtered. A run without {@code benchmarkRateRef}
 * fails rather than resetting accounts of benchmarks that reset on a different cycle.
 */
@Component
public class RateResetMonthlyBatchJob extends AbstractAccountRangeChunkJob<Long> {

    private final LendingServiceClient lending;

    public RateResetMonthlyBatchJob(DataSource dataSource, LendingServiceClient lending) {
        super(dataSource);
        this.lending = lending;
    }

    @Override
    public String getJobName() {
        return "RATE_RESET_MONTHLY";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE' AND rate_type = 'FLOATING' AND benchmark_rate_ref IS NOT NULL";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            String benchmark = JobParams.current().requireString("benchmarkRateRef");
            LendingServiceClient.RateResetResult result =
                    lending.resetRate(accountId, BatchJobContext.businessDate(), benchmark);
            return result.reset() ? accountId : null;
        };
    }
}
