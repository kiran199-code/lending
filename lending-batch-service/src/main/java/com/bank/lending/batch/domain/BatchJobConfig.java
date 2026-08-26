package com.bank.lending.batch.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One row per registered batch job. This is the single source of truth the framework reads
 * to assemble a Spring Batch {@link org.springframework.batch.core.Job}, schedule its Quartz
 * trigger, and apply its retry/skip/partitioning behavior — no per-job Java config is needed
 * to change any of these values, only a row update followed by a config reload
 * (see BatchAdminController#reloadConfig).
 *
 * <p>retryableExceptions / skippableExceptions / jobParamsJson are stored as jsonb: the first
 * two are JSON arrays of fully-qualified exception class names, the last is a free-form JSON
 * object of job-specific parameters (e.g. {"npaDpdThreshold": 90, "provisioningPct": {...}}).
 */
@Entity
@Table(name = "batch_job_config", schema = "batch")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchJobConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "config_id")
    private Long configId;

    /** Business key — matches BusinessBatchTasklet#getJobName() / BusinessChunkJob#getJobName(). */
    @Column(name = "job_name", nullable = false, unique = true, length = 100)
    private String jobName;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_category", nullable = false, length = 20)
    private JobCategory jobCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 10)
    private JobType jobType;

    /** Null/blank for event-triggered jobs — see JobCategory.EVENT_TRIGGERED. */
    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "restartable", nullable = false)
    private Boolean restartable;

    @Column(name = "chunk_size")
    private Integer chunkSize;

    /** Partition grid size for chunk jobs; ignored for tasklet-style jobs. */
    @Column(name = "concurrency")
    private Integer concurrency;

    /** Bound on the TaskExecutorPartitionHandler's thread pool — independent of grid size. */
    @Column(name = "throttle_limit")
    private Integer throttleLimit;

    @Column(name = "retry_limit", nullable = false)
    private Integer retryLimit;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retryable_exceptions", columnDefinition = "jsonb")
    private String retryableExceptions;

    @Column(name = "skip_limit", nullable = false)
    private Integer skipLimit;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skippable_exceptions", columnDefinition = "jsonb")
    private String skippableExceptions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "job_params_json", columnDefinition = "jsonb")
    private String jobParamsJson;

    /** Lower runs first when multiple jobs are due at the same Quartz trigger tick. */
    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "timeout_minutes")
    private Integer timeoutMinutes;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public enum JobCategory { DAILY, MONTHLY, PERIODIC, EVENT_TRIGGERED }

    public enum JobType { CHUNK, TASKLET }
}
