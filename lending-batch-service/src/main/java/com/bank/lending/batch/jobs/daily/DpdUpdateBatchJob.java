package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: recomputes days-past-due (DPD) for every account's oldest unpaid installment.
 *
 * <p>TODO: business logic — for accountId, find the oldest amortization_schedule row not in
 * status PAID with due_date on or before {@link BatchJobContext#businessDate()} (same lookup as
 * lending-service's LoanAccountService#classifyAccount), compute
 * {@code businessDate - due_date} in days, and persist it (a dedicated dpd tracking
 * column/table — not yet modeled — rather than loan_account.account_status directly).
 * NPA_CLASSIFICATION_CHECK_DAILY consumes this output to assign IRAC buckets, so this job's
 * batch_job_config.priority must be lower (i.e. run earlier) than that job's.
 */
@Component
public class DpdUpdateBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public DpdUpdateBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "DPD_UPDATE_DAILY";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — recompute and persist DPD for accountId's oldest unpaid installment.
            return accountId;
        };
    }
}
