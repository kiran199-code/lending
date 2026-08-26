package com.bank.lending.batch.core;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Wraps a tasklet-style job's Tasklet so batch_job_config.timeout_minutes is actually enforced:
 * a runaway single-action job (e.g. a bureau extract call that hangs) is cancelled and the step
 * fails with a clear TimeoutException instead of blocking indefinitely. Only applied when a job's
 * config sets timeout_minutes — see BatchJobRegistry#buildTaskletJob.
 */
@RequiredArgsConstructor
public class TimeoutTaskletDecorator implements Tasklet {

    private static final ExecutorService GUARD_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "batch-tasklet-timeout-guard");
        t.setDaemon(true);
        return t;
    });

    private final Tasklet delegate;
    private final int timeoutMinutes;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        Future<RepeatStatus> future = GUARD_EXECUTOR.submit(() -> delegate.execute(contribution, chunkContext));
        try {
            return future.get(timeoutMinutes, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("Tasklet exceeded configured timeout of " + timeoutMinutes + " minute(s)");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }
}
