package com.bank.lending.batch.jobs.monthly;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Monthly (tasklet — one aggregate reconciliation, not per-account): reconciles co-lending /
 * business-correspondent (BC) partner fee-sharing splits.
 *
 * <p>TODO: business logic — for the month ending at {@link BatchJobContext#businessDate()},
 * sum disbursements/collections attributable to each partner (partner attribution model not yet
 * on loan_account — would need a partner_ref column or a co_lending_split table), compute the
 * agreed fee split, and generate the true-up settlement statement for each partner.
 */
@Component
public class FeeTrueUpPartnerReconciliationBatchJob implements BusinessBatchTasklet {

    @Override
    public String getJobName() {
        return "FEE_TRUE_UP_PARTNER_RECONCILIATION_MONTHLY";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            // TODO: business logic — reconcile and generate the monthly partner fee true-up.
            return RepeatStatus.FINISHED;
        };
    }
}
