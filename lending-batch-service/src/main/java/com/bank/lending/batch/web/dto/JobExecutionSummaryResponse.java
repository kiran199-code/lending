package com.bank.lending.batch.web.dto;

import org.springframework.batch.core.JobExecution;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record JobExecutionSummaryResponse(
        Long jobExecutionId,
        String jobName,
        String status,
        String exitCode,
        LocalDate businessDate,
        LocalDateTime startTime,
        LocalDateTime endTime,
        long readCount,
        long writeCount,
        long skipCount
) {
    public static JobExecutionSummaryResponse from(JobExecution e) {
        long read = 0, write = 0, skip = 0;
        for (var step : e.getStepExecutions()) {
            read += step.getReadCount();
            write += step.getWriteCount();
            skip += step.getSkipCount();
        }
        return new JobExecutionSummaryResponse(
                e.getId(), e.getJobInstance().getJobName(), e.getStatus().toString(),
                e.getExitStatus().getExitCode(), e.getJobParameters().getLocalDate("businessDate"),
                e.getStartTime(), e.getEndTime(), read, write, skip
        );
    }
}
