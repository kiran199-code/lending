package com.bank.lending.batch.support;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Test-only chunk job (src/test — never packaged with the application) used by
 * {@code LendingBatchServiceIntegrationTest} to exercise the dead-letter path end to end: it
 * deliberately throws for one specific seeded account_id so a real skip + dead-letter record can
 * be asserted on, without depending on any of the 36 real jobs' still-stubbed business logic
 * (which never throws today). Its config row is inserted directly by the test, not seeded by
 * V2__seed_batch_job_config.sql.
 */
@Component
public class FailingTestChunkJob extends AbstractAccountRangeChunkJob<Long> {

    /** The one account_id in the test's seeded loan_account rows that this job always fails on. */
    public static final long POISON_ACCOUNT_ID = 42L;

    public FailingTestChunkJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "TEST_CHUNK_JOB_WITH_FAILURE";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            if (accountId == POISON_ACCOUNT_ID) {
                throw new SimulatedValidationException("Simulated business validation failure for account " + accountId);
            }
            return accountId;
        };
    }

    /** Registered as this test job's skippable_exceptions entry so the framework dead-letters (rather than fails) on it. */
    public static class SimulatedValidationException extends RuntimeException {
        public SimulatedValidationException(String message) {
            super(message);
        }
    }
}
