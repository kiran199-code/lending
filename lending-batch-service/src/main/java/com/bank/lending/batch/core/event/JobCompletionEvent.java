package com.bank.lending.batch.core.event;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Internal Spring application event published by MetricsJobExecutionListener after every job
 * run. Deliberately decoupled from Kafka itself — KafkaJobEventBridge is the only class that
 * knows this becomes a Kafka message, so the batch job code (and this event) stays free of
 * messaging concerns and is trivially testable without a broker.
 */
public record JobCompletionEvent(
        String jobName,
        Long jobExecutionId,
        LocalDate businessDate,
        String status,
        String exitCode,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        long readCount,
        long writeCount,
        long skipCount
) {
}
