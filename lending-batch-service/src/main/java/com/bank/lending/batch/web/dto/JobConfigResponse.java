package com.bank.lending.batch.web.dto;

import com.bank.lending.batch.domain.BatchJobConfig;

public record JobConfigResponse(
        Long configId,
        String jobName,
        String jobCategory,
        String jobType,
        String cronExpression,
        Boolean enabled,
        Boolean restartable,
        Integer chunkSize,
        Integer concurrency,
        Integer throttleLimit,
        Integer retryLimit,
        Integer skipLimit,
        Integer priority,
        Integer timeoutMinutes,
        boolean assembled
) {
    public static JobConfigResponse from(BatchJobConfig c, boolean assembled) {
        return new JobConfigResponse(
                c.getConfigId(), c.getJobName(), c.getJobCategory().name(), c.getJobType().name(),
                c.getCronExpression(), c.getEnabled(), c.getRestartable(), c.getChunkSize(),
                c.getConcurrency(), c.getThrottleLimit(), c.getRetryLimit(), c.getSkipLimit(),
                c.getPriority(), c.getTimeoutMinutes(), assembled
        );
    }
}
