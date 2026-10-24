package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.chit.ChitServiceClient;
import com.bank.lending.batch.chit.ChitServiceClient.RunResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Daily EOD (tasklet): flags chit tickets that have breached their group's default threshold (too many unpaid
 * instalments, or one that is too old). Calls {@code POST /api/v1/chit/batch/default-run}. Runs after the late-fee
 * job. It only flags; recovery and write-off are separate, manual steps in the chit service.
 */
@Component
public class ChitDefaultCheckBatchJob extends AbstractChitDailyJob {

    public ChitDefaultCheckBatchJob(ChitServiceClient chit) {
        super(chit);
    }

    @Override
    public String getJobName() {
        return "CHIT_DEFAULT_CHECK_DAILY";
    }

    @Override
    RunResult call(ChitServiceClient chit, LocalDate businessDate) {
        return chit.runDefaultCheck(businessDate);
    }
}
