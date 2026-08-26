package com.bank.lending.batch.jobs.monthly;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Monthly: generates/confirms the upcoming billing-cycle EMI bill for every active account.
 *
 * <p>TODO: business logic — for accountId, read the next pending amortization_schedule
 * installment (amount, due date) and produce the bill record the statement/e-notification
 * pipeline consumes (see STATEMENT_NOTIFICATION_TRIGGER_DAILY). Distinct from EMI collection
 * itself — this job only confirms what's owed, it never posts a payment.
 */
@Component
public class EmiBillingGenerationBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public EmiBillingGenerationBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "EMI_BILLING_GENERATION_MONTHLY";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — generate accountId's next billing-cycle EMI bill.
            return accountId;
        };
    }
}
