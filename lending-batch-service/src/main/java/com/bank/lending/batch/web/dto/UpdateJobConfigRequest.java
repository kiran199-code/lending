package com.bank.lending.batch.web.dto;

/**
 * Partial update of one batch_job_config row. A null field means "leave unchanged".
 * {@code expectedVersion}, when given, must match the row's current version or the update is
 * rejected with 409 (guards against two operators editing the same job at once).
 */
public record UpdateJobConfigRequest(
        Boolean enabled,
        String cronExpression,
        Boolean restartable,
        Integer chunkSize,
        Integer concurrency,
        Integer throttleLimit,
        Integer retryLimit,
        Integer skipLimit,
        Integer priority,
        Integer timeoutMinutes,
        Integer expectedVersion
) {
}
