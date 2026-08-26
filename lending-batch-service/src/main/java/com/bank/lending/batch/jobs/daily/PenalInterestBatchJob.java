package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: calculates penal/overdue interest on installments that are past due.
 *
 * <p>TODO: business logic — for each account with at least one amortization_schedule row where
 * installment_status = 'OVERDUE', compute penal interest on the overdue principal + interest
 * component at the product's penal rate (read from {@link BatchJobContext#jobParamsJson()}, e.g.
 * {@code {"penalRateBps": 200}}), for the number of days past due as of
 * {@link BatchJobContext#businessDate()}. Persist as a pending charge (would eventually post via
 * an emi_transaction with transaction_type=CHARGE, alongside CHARGES_FEE_APPLICATION_DAILY).
 */
@Component
public class PenalInterestBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public PenalInterestBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "PENAL_INTEREST_CALCULATION_DAILY";
    }

    @Override
    protected String accountFilter() {
        return "account_status IN ('NPA_SMA0','NPA_SMA1','NPA_SMA2','NPA_SUBSTANDARD','NPA_DOUBTFUL')";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — compute and record penal interest for overdue installments on accountId.
            return accountId;
        };
    }
}
