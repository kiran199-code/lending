-- Dedicated schema for everything owned by lending-batch-service: framework metadata tables
-- below, plus Spring Batch's own BATCH_* tables and Quartz's QRTZ_* tables (see V3), all kept
-- out of the lending-service application's "public" schema.
CREATE SCHEMA IF NOT EXISTS batch;

-- ---------------------------------------------------------------------------------------------
-- batch_job_config: single source of truth the framework reads at startup (BatchJobRegistry) and
-- on POST /api/v1/batch-admin/config/reload to assemble each Job, schedule its Quartz trigger,
-- and apply its retry/skip/partitioning behavior. One row per job_name.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE batch.batch_job_config (
    config_id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_name                VARCHAR(100) NOT NULL,
    job_category             VARCHAR(20)  NOT NULL,
    job_type                 VARCHAR(10)  NOT NULL,
    cron_expression          VARCHAR(100),
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    restartable              BOOLEAN      NOT NULL DEFAULT TRUE,
    chunk_size               INTEGER,
    concurrency               INTEGER,
    throttle_limit            INTEGER,
    retry_limit               INTEGER      NOT NULL DEFAULT 0,
    retryable_exceptions      JSONB,
    skip_limit                INTEGER      NOT NULL DEFAULT 0,
    skippable_exceptions      JSONB,
    job_params_json           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    priority                  INTEGER      NOT NULL DEFAULT 100,
    timeout_minutes           INTEGER,
    version                   INTEGER      NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_batch_job_config_job_name UNIQUE (job_name),
    CONSTRAINT chk_batch_job_config_job_category
        CHECK (job_category IN ('DAILY', 'MONTHLY', 'PERIODIC', 'EVENT_TRIGGERED')),
    CONSTRAINT chk_batch_job_config_job_type CHECK (job_type IN ('CHUNK', 'TASKLET')),
    CONSTRAINT chk_batch_job_config_cron_required
        CHECK (job_category = 'EVENT_TRIGGERED' OR cron_expression IS NOT NULL)
);

CREATE INDEX idx_batch_job_config_enabled ON batch.batch_job_config (enabled);
CREATE INDEX idx_batch_job_config_category ON batch.batch_job_config (job_category);

-- ---------------------------------------------------------------------------------------------
-- batch_job_audit_log: step-level execution summary, one row per (job execution, step), written
-- by AuditStepExecutionListener#afterStep for every step of every run.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE batch.batch_job_audit_log (
    audit_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_name             VARCHAR(100) NOT NULL,
    step_name            VARCHAR(150) NOT NULL,
    job_execution_id     BIGINT       NOT NULL,
    step_execution_id    BIGINT       NOT NULL,
    business_date        DATE,
    read_count           BIGINT,
    write_count          BIGINT,
    skip_count           BIGINT,
    retry_count          BIGINT,
    commit_count         BIGINT,
    rollback_count       BIGINT,
    exit_status          VARCHAR(30),
    exit_description     TEXT,
    start_time           TIMESTAMPTZ,
    end_time             TIMESTAMPTZ,
    duration_millis      BIGINT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_batch_job_audit_log_job_name ON batch.batch_job_audit_log (job_name, start_time DESC);
CREATE INDEX idx_batch_job_audit_log_job_execution_id ON batch.batch_job_audit_log (job_execution_id);

-- ---------------------------------------------------------------------------------------------
-- batch_job_error_records: dead-letter table, one row per skipped/failed item, written by
-- DeadLetterSkipListener (chunk jobs) or directly by a tasklet's own catch block.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE batch.batch_job_error_records (
    error_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_name            VARCHAR(100) NOT NULL,
    job_execution_id    BIGINT       NOT NULL,
    business_date       DATE,
    record_identifier   VARCHAR(200),
    record_payload      TEXT,
    exception_class     VARCHAR(300),
    exception_message   TEXT,
    stack_trace         TEXT,
    replay_status       VARCHAR(15)  NOT NULL DEFAULT 'PENDING_REVIEW',
    replayed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_batch_job_error_records_replay_status
        CHECK (replay_status IN ('PENDING_REVIEW', 'REPLAYED', 'IGNORED'))
);

CREATE INDEX idx_batch_job_error_records_job_name ON batch.batch_job_error_records (job_name, created_at DESC);
CREATE INDEX idx_batch_job_error_records_job_execution_id ON batch.batch_job_error_records (job_execution_id);
CREATE INDEX idx_batch_job_error_records_replay_status
    ON batch.batch_job_error_records (job_name, replay_status, created_at DESC);
