package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.chit.ChitServiceClient;
import com.bank.lending.batch.chit.ChitServiceClient.RunResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Daily EOD (tasklet): charges the chit late fee, one day at a time, on every overdue instalment up to the business
 * date. Calls {@code POST /api/v1/chit/batch/late-fee-run}. A day is never charged twice and a skipped day is made
 * good by the next run. Runs after the demand run so a newly raised instalment is already on the books.
 */
@Component
public class ChitLateFeeBatchJob extends AbstractChitDailyJob {

    public ChitLateFeeBatchJob(ChitServiceClient chit) {
        super(chit);
    }

    @Override
    public String getJobName() {
        return "CHIT_LATE_FEE_DAILY";
    }

    @Override
    RunResult call(ChitServiceClient chit, LocalDate businessDate) {
        return chit.runLateFees(businessDate);
    }
}
