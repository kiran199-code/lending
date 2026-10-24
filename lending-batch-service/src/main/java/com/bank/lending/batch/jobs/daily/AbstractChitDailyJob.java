package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.chit.ChitServiceClient;
import com.bank.lending.batch.chit.ChitServiceClient.RunResult;
import com.bank.lending.batch.core.BatchJobContext;
import com.bank.lending.batch.core.BusinessBatchTasklet;
import com.bank.lending.batch.core.JobParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.time.LocalDate;

/**
 * Shape shared by the three chit jobs: one call to the chit service for the run's business date, which does the work
 * group by group and answers with what it did. The job fails (and so holds end of day on this date) when any group
 * could not be finished, naming the groups; running it again finishes only what is left, because every chit job is
 * safe to repeat.
 *
 * <p>With no chit service URL the job logs a warning and finishes, so it can never hold up the loan end of day on a
 * chit service that has not been set up. Set the job parameter {@code "requireChitService": true} to fail instead.
 */
abstract class AbstractChitDailyJob implements BusinessBatchTasklet {

    /** Job parameter: true makes a missing chit service URL fail the job instead of skipping it. Default false. */
    static final String REQUIRE_CHIT_SERVICE = "requireChitService";

    private static final Logger log = LoggerFactory.getLogger(AbstractChitDailyJob.class);

    private final ChitServiceClient chit;

    AbstractChitDailyJob(ChitServiceClient chit) {
        this.chit = chit;
    }

    /** The call to make for the date. */
    abstract RunResult call(ChitServiceClient chit, LocalDate businessDate);

    @Override
    public Tasklet buildTasklet() {
        return (contribution, chunkContext) -> {
            LocalDate day = BatchJobContext.businessDate();
            if (!chit.configured()) {
                String message = getJobName() + " has nothing to call: CHIT_SERVICE_URL is not set";
                if (JobParams.current().booleanValue(REQUIRE_CHIT_SERVICE, false)) {
                    throw new IllegalStateException(message);
                }
                log.warn("{} (skipped; set \"{}\": true in the job parameters to fail instead)", message, REQUIRE_CHIT_SERVICE);
                return RepeatStatus.FINISHED;
            }
            RunResult result = call(chit, day);
            if (!result.clean()) {
                throw new IllegalStateException(getJobName() + " for " + day + ": " + result.groupsFailed() + " group(s) failed, "
                        + result.groupsProcessed() + " done. " + result.failuresText());
            }
            log.info("{} for {}: {} group(s), {}", getJobName(), day, result.groupsProcessed(), result.figuresText());
            return RepeatStatus.FINISHED;
        };
    }
}
