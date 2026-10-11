package com.bank.lending.batch.core;

import com.bank.lending.batch.core.validation.BatchJobConfigValidator;
import com.bank.lending.batch.domain.BatchJobConfig;
import com.bank.lending.batch.repository.BatchJobConfigRepository;
import com.bank.lending.batch.web.dto.UpdateJobConfigRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validated edits to batch_job_config. The change is persisted here; it only takes effect on the
 * running scheduler after POST /api/v1/batch-admin/config/reload (jobs are assembled from the
 * table at startup and on reload).
 */
@Service
@RequiredArgsConstructor
public class BatchJobConfigService {

    private final BatchJobConfigRepository repository;
    private final BatchJobConfigValidator validator;

    @Transactional
    public BatchJobConfig update(String jobName, UpdateJobConfigRequest request) {
        BatchJobConfig config = repository.findByJobName(jobName)
                .orElseThrow(() -> new IllegalArgumentException("No such job: " + jobName));

        if (request.expectedVersion() != null && !request.expectedVersion().equals(config.getVersion())) {
            throw new OptimisticLockingFailureException("Job '" + jobName + "' was changed by someone else (version "
                    + config.getVersion() + ", you edited version " + request.expectedVersion() + "). Reload and retry.");
        }

        if (request.enabled() != null) config.setEnabled(request.enabled());
        if (request.cronExpression() != null) {
            String cron = request.cronExpression().trim();
            config.setCronExpression(cron.isEmpty() ? null : cron);
        }
        if (request.restartable() != null) config.setRestartable(request.restartable());
        if (request.chunkSize() != null) config.setChunkSize(request.chunkSize());
        if (request.concurrency() != null) config.setConcurrency(request.concurrency());
        if (request.throttleLimit() != null) config.setThrottleLimit(request.throttleLimit());
        if (request.retryLimit() != null) config.setRetryLimit(request.retryLimit());
        if (request.skipLimit() != null) config.setSkipLimit(request.skipLimit());
        if (request.priority() != null) config.setPriority(request.priority());
        if (request.timeoutMinutes() != null) config.setTimeoutMinutes(request.timeoutMinutes());

        // Same rules the registry applies at startup/reload; throws IllegalArgumentException -> rolled back.
        validator.validate(config);
        return repository.saveAndFlush(config);
    }
}
