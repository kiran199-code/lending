package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.chit.ChitServiceClient;
import com.bank.lending.batch.chit.ChitServiceClient.RunResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Daily EOD (tasklet): opens every chit cycle whose instalment date has been reached and raises each subscriber's
 * instalment, using any advance money held. Calls {@code POST /api/v1/chit/batch/demand-run}. A missed day is caught up
 * by the next run; a repeated run changes nothing. Runs right after the loan demand run (priority 5) and before the
 * late-fee and default jobs.
 */
@Component
public class ChitDemandRaiseBatchJob extends AbstractChitDailyJob {

    public ChitDemandRaiseBatchJob(ChitServiceClient chit) {
        super(chit);
    }

    @Override
    public String getJobName() {
        return "CHIT_DEMAND_RAISE_DAILY";
    }

    @Override
    RunResult call(ChitServiceClient chit, LocalDate businessDate) {
        return chit.runDemands(businessDate);
    }
}
