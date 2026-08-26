package com.bank.lending.batch.jobs.monthly;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Monthly (tasklet — one aggregate reporting action, not per-account): generates RBI returns and
 * Priority Sector Lending (PSL) classification reports.
 *
 * <p>TODO: business logic — aggregate the month's loan_account and provisioning data (from
 * PROVISIONING_CALCULATION_MONTHLY) into each report named in
 * {@link BatchJobContext#jobParamsJson()} (e.g. {@code {"reportCodes": ["RBI_XBRL", "PSL_ANNEX"]}})
 * and submit/export per that report's required format.
 */
@Component
public class RegulatoryReportingBatchJob implements BusinessBatchTasklet {

    @Override
    public String getJobName() {
        return "REGULATORY_REPORTING_MONTHLY";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            // TODO: business logic — generate the month's regulatory reports.
            return RepeatStatus.FINISHED;
        };
    }
}
