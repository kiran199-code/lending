package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.lending.LendingServiceClient;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: charges penal interest on the principal and interest that are still unpaid on overdue demands.
 *
 * <p>Calls {@code POST /api/v1/loan-accounts/{id}/penal-interest?businessDate=}. lending-service reads the rate, grace
 * days and cap from the account's loan product (Products screen), charges every day up to and including the business
 * date that has not been charged yet (so a missed run is caught up on the next one), and writes one row per demand per
 * day, so a re-run or a retried chunk never charges a day twice. The charge is flat: it is not added to the interest
 * rate and never earns penal interest. Items that were charged nothing are filtered, so the step's "write count" is the
 * number of accounts that were charged today.
 *
 * <p>Runs after DEMAND_RAISE_DAILY (priority 5) so that today's demands exist. The seeded {@code job_params_json} has
 * no effect here: all penal settings live on the product.
 */
@Component
public class PenalInterestBatchJob extends AbstractAccountRangeChunkJob<Long> {

    private final LendingServiceClient lending;

    public PenalInterestBatchJob(DataSource dataSource, LendingServiceClient lending) {
        super(dataSource);
        this.lending = lending;
    }

    @Override
    public String getJobName() {
        return "PENAL_INTEREST_CALCULATION_DAILY";
    }

    @Override
    protected String accountFilter() {
        return "(account_status = 'ACTIVE' OR left(account_status, 4) = 'NPA_')";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            LendingServiceClient.PenalAccrualResult result =
                    lending.accruePenalInterest(accountId, BatchJobContext.businessDate());
            return result.accrualsCreated() > 0 ? accountId : null;
        };
    }
}
