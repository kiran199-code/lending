package com.bank.lending.batch.core.validation;

import com.bank.lending.batch.domain.BatchJobConfig;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Applied to every batch_job_config row on load (startup) and reload (admin endpoint) — a
 * malformed row is rejected with a clear message here rather than surfacing later as a confusing
 * NPE deep inside job assembly or Quartz scheduling.
 *
 * <p>Cron validity is checked with Spring's {@link CronExpression}, which is a close but not
 * byte-for-byte match of Quartz's own parser (notably Quartz requires exactly one of the
 * day-of-month/day-of-week fields to be {@code ?}). Treat this as a fast pre-check — QuartzJobBridge
 * will still surface a clear SchedulerException if Quartz itself rejects an expression that
 * passed this check.
 */
@Component
public class BatchJobConfigValidator {

    public void validate(BatchJobConfig config) {
        List<String> errors = new ArrayList<>();

        if (config.getJobName() == null || config.getJobName().isBlank()) {
            errors.add("job_name must not be blank");
        }
        if (config.getJobCategory() != BatchJobConfig.JobCategory.EVENT_TRIGGERED
                && (config.getCronExpression() == null || config.getCronExpression().isBlank())) {
            errors.add("cron_expression is required for non-event-triggered jobs");
        }
        if (config.getCronExpression() != null && !config.getCronExpression().isBlank()
                && !CronExpression.isValidExpression(config.getCronExpression())) {
            errors.add("cron_expression '" + config.getCronExpression() + "' is not a valid cron expression");
        }
        if (config.getJobType() == BatchJobConfig.JobType.CHUNK) {
            if (config.getChunkSize() == null || config.getChunkSize() <= 0) {
                errors.add("chunk_size must be > 0 for a CHUNK job");
            }
            if (config.getConcurrency() != null && config.getConcurrency() < 1) {
                errors.add("concurrency must be >= 1");
            }
            if (config.getThrottleLimit() != null && config.getThrottleLimit() < 1) {
                errors.add("throttle_limit must be >= 1");
            }
        }
        if (config.getRetryLimit() != null && config.getRetryLimit() < 0) {
            errors.add("retry_limit must be >= 0");
        }
        if (config.getSkipLimit() != null && config.getSkipLimit() < 0) {
            errors.add("skip_limit must be >= 0");
        }
        if (config.getTimeoutMinutes() != null && config.getTimeoutMinutes() <= 0) {
            errors.add("timeout_minutes must be > 0 when set");
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid batch_job_config for job '" + config.getJobName()
                    + "': " + String.join("; ", errors));
        }
    }
}
