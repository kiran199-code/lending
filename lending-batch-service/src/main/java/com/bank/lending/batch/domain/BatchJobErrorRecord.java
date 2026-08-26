package com.bank.lending.batch.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Dead-letter table: one row per skipped/failed item, written by DeadLetterSkipListener (chunk
 * jobs) or directly by a tasklet's own catch block (tasklet-style jobs). recordPayload holds
 * enough of the failed item (as JSON) to support manual review and replay without re-deriving
 * it from the source tables — see README "Error records: surfacing and replay".
 */
@Entity
@Table(name = "batch_job_error_records", schema = "batch")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchJobErrorRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "error_id")
    private Long errorId;

    @Column(name = "job_name", nullable = false, length = 100)
    private String jobName;

    @Column(name = "job_execution_id", nullable = false)
    private Long jobExecutionId;

    @Column(name = "business_date")
    private LocalDate businessDate;

    /** e.g. the loan account_id the failed record belonged to — the natural key for replay. */
    @Column(name = "record_identifier", length = 200)
    private String recordIdentifier;

    @Column(name = "record_payload", columnDefinition = "TEXT")
    private String recordPayload;

    @Column(name = "exception_class", length = 300)
    private String exceptionClass;

    @Column(name = "exception_message", columnDefinition = "TEXT")
    private String exceptionMessage;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Enumerated(EnumType.STRING)
    @Column(name = "replay_status", nullable = false, length = 15)
    private ReplayStatus replayStatus;

    @Column(name = "replayed_at")
    private OffsetDateTime replayedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum ReplayStatus { PENDING_REVIEW, REPLAYED, IGNORED }
}
