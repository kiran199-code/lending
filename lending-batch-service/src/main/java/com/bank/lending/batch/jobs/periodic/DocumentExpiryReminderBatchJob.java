package com.bank.lending.batch.jobs.periodic;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Periodic (run quarterly by default via cron_expression, adjustable through config): scans for
 * KYC and collateral documents nearing expiry and triggers renewal reminders.
 *
 * <p>TODO: business logic — for accountId, check KYC re-verification due date and, for
 * account_type=MORTGAGE, mortgage document/valuation currency (see also
 * ANNUAL_INSURANCE_RENEWAL_CHECK for the insurance-specific case); if any expire within {@code
 * job_params_json.reminderWindowDays} (default 45) of {@link BatchJobContext#businessDate()},
 * trigger a renewal/re-verification reminder.
 */
@Component
public class DocumentExpiryReminderBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public DocumentExpiryReminderBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "DOCUMENT_EXPIRY_REMINDER";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — check accountId's KYC/collateral document expiry and trigger reminders.
            return accountId;
        };
    }
}
