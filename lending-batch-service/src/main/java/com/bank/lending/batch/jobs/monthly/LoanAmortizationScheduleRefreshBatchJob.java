package com.bank.lending.batch.jobs.monthly;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Monthly: sanity-refreshes each active account's amortization schedule against its current
 * loan_account state.
 *
 * <p>TODO: business logic — for accountId, verify the current-version amortization_schedule's
 * remaining installments are consistent with loan_account.interest_rate, tenure, and outstanding
 * principal; regenerate (via the same mechanics as AmortizationScheduleService#regenerateSchedule)
 * if drifted. Acts as a monthly consistency sweep on top of the event-driven regenerations that
 * rate resets and part-payments already trigger.
 */
@Component
public class LoanAmortizationScheduleRefreshBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public LoanAmortizationScheduleRefreshBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "LOAN_AMORTIZATION_SCHEDULE_REFRESH_MONTHLY";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — verify/refresh accountId's amortization schedule.
            return accountId;
        };
    }
}
