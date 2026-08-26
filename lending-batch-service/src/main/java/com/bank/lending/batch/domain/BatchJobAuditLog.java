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
 * Step-level execution summary written by AuditStepExecutionListener#afterStep for every step
 * of every job run — one row per (job execution, step). Distinct from batch_job_error_records,
 * which holds per-item dead-letter detail; this table answers "how did each run of each step go"
 * (read counts, skip/retry counts, duration, exit status) for the ops/admin view.
 */
@Entity
@Table(name = "batch_job_audit_log", schema = "batch")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchJobAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long auditId;

    @Column(name = "job_name", nullable = false, length = 100)
    private String jobName;

    @Column(name = "step_name", nullable = false, length = 150)
    private String stepName;

    @Column(name = "job_execution_id", nullable = false)
    private Long jobExecutionId;

    @Column(name = "step_execution_id", nullable = false)
    private Long stepExecutionId;

    @Column(name = "business_date")
    private LocalDate businessDate;

    @Column(name = "read_count")
    private Long readCount;

    @Column(name = "write_count")
    private Long writeCount;

    @Column(name = "skip_count")
    private Long skipCount;

    @Column(name = "retry_count")
    private Long retryCount;

    @Column(name = "commit_count")
    private Long commitCount;

    @Column(name = "rollback_count")
    private Long rollbackCount;

    @Column(name = "exit_status", length = 30)
    private String exitStatus;

    @Column(name = "exit_description", columnDefinition = "TEXT")
    private String exitDescription;

    @Column(name = "start_time")
    private OffsetDateTime startTime;

    @Column(name = "end_time")
    private OffsetDateTime endTime;

    @Column(name = "duration_millis")
    private Long durationMillis;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
