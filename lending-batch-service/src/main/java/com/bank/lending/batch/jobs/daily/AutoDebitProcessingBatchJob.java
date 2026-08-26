package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: initiates auto-debit / standing-instruction collection for accounts with an EMI
 * due today.
 *
 * <p>TODO: business logic — for accountId with an active NACH/SI mandate (mandate model not yet
 * built — a future migration would add a standing_instruction table) and a pending
 * amortization_schedule installment due on {@link BatchJobContext#businessDate()}, submit the
 * auto-debit instruction to the payment rail and, on same-day confirmation, post the result via
 * the same mechanics as lending-service's EmiPaymentService#postEmiPayment (channel=NACH). Failed
 * attempts are picked up the next day by BOUNCE_NSF_HANDLING_DAILY.
 */
@Component
public class AutoDebitProcessingBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public AutoDebitProcessingBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "AUTO_DEBIT_PROCESSING_DAILY";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — submit auto-debit instruction for accountId's due installment, if any.
            return accountId;
        };
    }
}
