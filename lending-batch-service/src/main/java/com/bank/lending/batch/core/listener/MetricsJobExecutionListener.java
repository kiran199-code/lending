package com.bank.lending.batch.core.listener;

import com.bank.lending.batch.core.JobParamsUtil;
import com.bank.lending.batch.core.event.JobCompletionEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Attached to every job the registry assembles (not per-job code): logs start/end, emits
 * Micrometer metrics (duration, read/write/skip counts, tagged by job name), and publishes a
 * {@link JobCompletionEvent} for {@code KafkaJobEventBridge} to forward downstream.
 */
@Slf4j
@RequiredArgsConstructor
public class MetricsJobExecutionListener implements JobExecutionListener {

    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private Timer.Sample sample;

    @Override
    public void beforeJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        log.info("Batch job starting: {} executionId={} params={}",
                jobName, jobExecution.getId(), jobExecution.getJobParameters());
        sample = Timer.start(meterRegistry);
        meterRegistry.counter("batch.job.started", "job", jobName).increment();
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        String status = jobExecution.getStatus().toString();

        if (sample != null) {
            sample.stop(Timer.builder("batch.job.duration")
                    .tag("job", jobName)
                    .tag("status", status)
                    .register(meterRegistry));
        }

        long readCount = 0, writeCount = 0, skipCount = 0;
        for (StepExecution step : jobExecution.getStepExecutions()) {
            readCount += step.getReadCount();
            writeCount += step.getWriteCount();
            skipCount += step.getSkipCount();
        }
        meterRegistry.counter("batch.job.records.read", "job", jobName).increment(readCount);
        meterRegistry.counter("batch.job.records.written", "job", jobName).increment(writeCount);
        meterRegistry.counter("batch.job.records.skipped", "job", jobName).increment(skipCount);
        if (jobExecution.getStatus().isUnsuccessful()) {
            meterRegistry.counter("batch.job.failed", "job", jobName).increment();
        }

        log.info("Batch job finished: {} executionId={} status={} read={} written={} skipped={}",
                jobName, jobExecution.getId(), status, readCount, writeCount, skipCount);

        LocalDate businessDate = JobParamsUtil.businessDate(jobExecution.getJobParameters());
        eventPublisher.publishEvent(new JobCompletionEvent(
                jobName,
                jobExecution.getId(),
                businessDate,
                status,
                jobExecution.getExitStatus().getExitCode(),
                toOffsetDateTime(jobExecution.getStartTime()),
                toOffsetDateTime(jobExecution.getEndTime()),
                readCount, writeCount, skipCount
        ));
    }

    private OffsetDateTime toOffsetDateTime(java.time.LocalDateTime localDateTime) {
        return localDateTime == null ? null : localDateTime.atOffset(ZoneOffset.UTC);
    }
}
