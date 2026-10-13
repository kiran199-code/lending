package com.bank.lending.batch.core.eod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

/** Spring-free: end of day must wait for an asynchronously launched job instead of judging it while it is still starting. */
class SpringBatchEodJobGatewayTest {

    private static JobExecution execution(BatchStatus status) {
        JobExecution e = new JobExecution(7L);
        e.setStatus(status);
        return e;
    }

    @Test
    void waitsUntilTheJobReachesAFinalState() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        JobExecution result = SpringBatchEodJobGateway.awaitTerminal(execution(BatchStatus.STARTING),
                id -> execution(polls.incrementAndGet() < 3 ? BatchStatus.STARTED : BatchStatus.COMPLETED),
                Duration.ofSeconds(5), Duration.ofMillis(10));
        assertEquals(BatchStatus.COMPLETED, result.getStatus());
        assertEquals(3, polls.get());
    }

    @Test
    void anAlreadyFinishedJobIsNotPolled() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        JobExecution result = SpringBatchEodJobGateway.awaitTerminal(execution(BatchStatus.FAILED),
                id -> {
                    polls.incrementAndGet();
                    return null;
                }, Duration.ofSeconds(5), Duration.ofMillis(10));
        assertEquals(BatchStatus.FAILED, result.getStatus());
        assertEquals(0, polls.get());
    }

    @Test
    void givesUpAfterTheTimeoutAndReportsTheJobAsStillRunning() throws Exception {
        JobExecution result = SpringBatchEodJobGateway.awaitTerminal(execution(BatchStatus.STARTED),
                id -> execution(BatchStatus.STARTED), Duration.ofMillis(60), Duration.ofMillis(10));
        assertTrue(result.getStatus().isRunning());
    }
}
