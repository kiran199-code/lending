package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: reconciles yesterday's auto-debit attempts against the bank's NACH return/bounce
 * file and handles non-sufficient-funds outcomes.
 *
 * <p>TODO: business logic — for accountId, match yesterday's AUTO_DEBIT_PROCESSING_DAILY
 * emi_transaction attempts (payment_channel=NACH) against the bank's return file (external
 * feed — not yet modeled) for {@link BatchJobContext#businessDate()}. On a bounce: mark the
 * transaction paymentStatus=FAILED, apply the configured bounce charge (see job_params_json,
 * e.g. {@code {"bounceChargeAmount": 500}}), and flag the account for collections follow-up.
 */
@Component
public class BounceNsfHandlingBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public BounceNsfHandlingBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "BOUNCE_NSF_HANDLING_DAILY";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — reconcile accountId's auto-debit attempt against the NACH return file.
            return accountId;
        };
    }
}
