package com.bank.lending.batch.jobs.daily;

import com.bank.lending.batch.core.AbstractAccountRangeChunkJob;
import com.bank.lending.batch.core.BatchJobContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Daily EOD: enqueues customer notifications for events that occurred (or are imminent) as of
 * today.
 *
 * <p>TODO: business logic — for accountId, check for any of: an EMI due within the reminder
 * window (job_params_json {@code {"reminderDaysBeforeDue": 3}}), a payment posted today, or a
 * DPD threshold crossed today, and enqueue the appropriate notification (SMS/email/push).
 * Notification dispatch is a separate downstream concern — publish to a dedicated Kafka topic
 * (distinct from KafkaJobEventBridge's internal job-completion topic) or call a
 * notification-service API; not yet wired.
 */
@Component
public class StatementNotificationTriggerBatchJob extends AbstractAccountRangeChunkJob<Long> {

    public StatementNotificationTriggerBatchJob(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    public String getJobName() {
        return "STATEMENT_NOTIFICATION_TRIGGER_DAILY";
    }

    @Override
    protected String accountFilter() {
        return "account_status = 'ACTIVE'";
    }

    @Override
    public ItemProcessor<Long, Long> processor() {
        return accountId -> {
            // TODO: business logic — evaluate and enqueue any due notifications for accountId.
            return accountId;
        };
    }
}
