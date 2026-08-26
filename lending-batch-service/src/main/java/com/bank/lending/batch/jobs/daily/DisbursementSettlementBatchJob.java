package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: settles approved-but-not-yet-disbursed sanctions due for payout today.
 *
 * <p>TODO: business logic — for accountId still PENDING_DISBURSEMENT with an approved
 * disbursement instruction dated {@link BatchJobContext#businessDate()} (instruction queue not
 * yet modeled — a future migration would add a disbursement_instruction table), initiate the
 * funds transfer to the borrower/vendor account and, on confirmation, call the same disbursement
 * mechanics as lending-service's LoanAccountService#disburse (generate the initial amortization
 * schedule, flip status to ACTIVE, write the DISBURSEMENT ledger_outbox entry). This is the
 * batch/bulk trigger for what is today only a synchronous API call in lending-service.
 */
@Component
public class DisbursementSettlementBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public DisbursementSettlementBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "DISBURSEMENT_SETTLEMENT_DAILY";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'PENDING_DISBURSEMENT'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — settle accountId's approved disbursement instruction, if due today.
            return accountId;
        };
    }
}
