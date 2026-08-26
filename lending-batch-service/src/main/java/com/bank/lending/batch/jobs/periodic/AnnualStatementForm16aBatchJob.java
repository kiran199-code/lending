package com.bank.lending.batch.jobs.periodic;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Annual: generates the Form 16A (TDS certificate) for interest components where applicable, and
 * the annual interest-paid statement, for the financial year ending at {@link
 * BatchJobContext#businessDate()}.
 *
 * <p>TODO: business logic — for accountId, sum the year's EMI interest components from
 * emi_transaction, compute TDS deducted (if any), and generate the Form 16A / annual interest
 * certificate document for the customer.
 */
@Component
public class AnnualStatementForm16aBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public AnnualStatementForm16aBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "ANNUAL_STATEMENT_FORM16A_GENERATION";
    }

    @Override
    protected String accountFilter() {
        return "account_status IN ('ACTIVE', 'CLOSED', 'FORECLOSED')";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — generate the Form 16A / annual interest statement for accountId.
            return accountId;
        };
    }
}
