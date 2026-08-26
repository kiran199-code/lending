package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: applies any charges/fees that fall due today (late fee, SI-failure fee, valuation
 * fee, etc.).
 *
 * <p>TODO: business logic — for accountId, evaluate the fee schedule in
 * {@link BatchJobContext#jobParamsJson()} (e.g.
 * {@code {"fees": [{"code": "LATE_FEE", "amount": 500, "triggerDpd": 1}]}}) against the account's
 * current state (DPD, recent bounce, etc.) and post each due fee as an emi_transaction with
 * transaction_type=CHARGE, same shape as EmiPaymentService's existing transaction posting.
 */
@Component
public class ChargesFeeApplicationBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public ChargesFeeApplicationBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "CHARGES_FEE_APPLICATION_DAILY";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — evaluate and post any due charges/fees for accountId.
            return accountId;
        };
    }
}
