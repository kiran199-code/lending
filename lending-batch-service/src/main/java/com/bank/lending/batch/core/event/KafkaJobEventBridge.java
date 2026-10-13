package com.bank.lending.batch.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * The only class in this codebase that knows job-completion events become Kafka messages —
 * everything upstream (listeners, jobs) just publishes/reacts to {@link JobCompletionEvent} via
 * Spring's in-process {@code ApplicationEventPublisher}. Downstream consumers (notifications,
 * GL posting triggers, bureau extract triggers per the task brief) subscribe to the
 * {@code lending.batch.job-events} topic; the key is the job name so consumers can partition
 * or filter per job type.
 */
@Slf4j
@ConditionalOnProperty(prefix = "batch.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
@Component
@RequiredArgsConstructor
public class KafkaJobEventBridge {

    private static final String TOPIC = "lending.batch.job-events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @EventListener
    public void onJobCompletion(JobCompletionEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(TOPIC, event.jobName(), payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish job-completion event for job={} executionId={}",
                                    event.jobName(), event.jobExecutionId(), ex);
                        }
                    });
        } catch (Exception e) {
            // Never let a Kafka/serialization problem fail the batch job itself — the job already
            // completed (or failed) on its own merits by the time this listener runs.
            log.error("Could not serialize/publish job-completion event for job={}", event.jobName(), e);
        }
    }
}
