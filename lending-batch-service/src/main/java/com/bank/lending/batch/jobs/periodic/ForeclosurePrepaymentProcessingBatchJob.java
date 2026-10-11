package com.bank.lending.batch.jobs.periodic;

import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import com.bank.lending.batch.core.JobParams;
import com.bank.lending.batch.lending.LendingServiceClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Event-triggered (tasklet; cron disabled — fired via the trigger API once a foreclosure /
 * full-prepayment request is approved): settles and closes one account.
 *
 * <p>Job params (per launch): {@code accountId} (required), {@code foreclosureDate} (ISO date,
 * default the run's business date) and {@code channel} (NACH | MANUAL | ONLINE, default MANUAL).
 * Calls {@code POST /api/v1/loan-accounts/{id}/payments/foreclosure}, where lending-service
 * computes the payoff (outstanding principal + accrued interest), posts the settlement and
 * closes the account. The call carries the idempotency key {@code FORECLOSURE-<accountId>-<date>},
 * so re-running the same request can never settle the account twice.
 *
 * <p>Not done yet: a separate payoff-quote call before posting (lending-service has no quote
 * endpoint).
 */
@Component
public class ForeclosurePrepaymentProcessingBatchJob implements BusinessBatchTasklet {

    private static final Logger log = LoggerFactory.getLogger(ForeclosurePrepaymentProcessingBatchJob.class);

    private final LendingServiceClient lending;

    public ForeclosurePrepaymentProcessingBatchJob(LendingServiceClient lending) {
        this.lending = lending;
    }

    @Override
    public String getJobName() {
        return "FORECLOSURE_PREPAYMENT_PROCESSING";
    }

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            JobParams params = JobParams.current();
            long accountId = params.requireLong("accountId");
            String dateText = params.string("foreclosureDate");
            LocalDate foreclosureDate = dateText != null ? LocalDate.parse(dateText) : BatchJobContext.businessDate();
            String channel = params.string("channel") != null ? params.string("channel") : "MANUAL";

            JsonNode txn = lending.foreclose(accountId, foreclosureDate, channel,
                    "FORECLOSURE-" + accountId + "-" + foreclosureDate);
            log.info("Foreclosed account {} on {} via {}: {}", accountId, foreclosureDate, channel, txn);
            return RepeatStatus.FINISHED;
        };
    }
}
