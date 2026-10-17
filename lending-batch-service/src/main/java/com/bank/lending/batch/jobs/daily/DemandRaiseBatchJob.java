package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.lending.LendingServiceClient;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD, first in the chain: raises the demand (principal + interest due) for every instalment that is
 * due on or before the business date and has no demand yet.
 *
 * <p>Calls {@code POST /api/v1/loan-accounts/{id}/demands?businessDate=}. lending-service creates one
 * {@code loan_demand} row per (account, instalment), so the call is idempotent: a re-run, a retried
 * chunk or a payment that already raised the demand never creates a second one. The job also catches
 * up an account that was missed on earlier days (it raises every due instalment, not only today's),
 * and for accounts that existed before demands were introduced it back-fills the history.
 *
 * <p>It runs before auto-debit and delinquency classification (lower priority number) so those see
 * the demand. Items that raised nothing are filtered, so the step's "write count" is the number of
 * accounts that received at least one new demand today.
 */
@Component
public class DemandRaiseBatchJob extends AbstractAccountRangeChunkJob<Long> {

    private final LendingServiceClient lending;

    public DemandRaiseBatchJob(DataSource dataSource, LendingServiceClient lending) {
        super(dataSource);
        this.lending = lending;
    }

    @Override
    public String getJobName() {
        return "DEMAND_RAISE_DAILY";
    }

    @Override
    protected String accountFilter() {
        return "(account_status = 'ACTIVE' OR left(account_status, 4) = 'NPA_')";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            LendingServiceClient.DemandRaiseResult result =
                    lending.raiseDemands(accountId, BatchJobContext.businessDate());
            return result.demandsCreated() > 0 ? accountId : null;
        };
    }
}
