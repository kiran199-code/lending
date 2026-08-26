-- Seeds one batch.batch_job_config row per business job stub under
-- com.bank.lending.batch.jobs.{daily,monthly,periodic} (36 total: 13 daily + 11 monthly +
-- 12 periodic, the last group covering quarterly, annual, and event-triggered jobs).
--
-- Cron expressions are Quartz 6-field (sec min hour day-of-month month day-of-week) and are
-- staggered so same-day jobs don't contend for the DB at the same tick; the daily chain in
-- particular preserves the dependency DPD_UPDATE_DAILY -> NPA_CLASSIFICATION_CHECK_DAILY called
-- out in both jobs' Javadoc, via priority ordering as well as cron offset. All times are server
-- local time (see application.yml scheduler.timezone).
--
-- chunk_size/concurrency/throttle_limit are NULL for TASKLET jobs (single-threaded, no
-- partitioning). retryable_exceptions defaults to transient data-access failures; job-specific
-- skippable_exceptions are left as a placeholder for the real business validation exceptions
-- each job will introduce once its TODO logic is implemented.

-- =================================================================================================
-- DAILY (13) — priority 10-130
-- =================================================================================================
INSERT INTO batch.batch_job_config
    (job_name, job_category, job_type, cron_expression, enabled, restartable,
     chunk_size, concurrency, throttle_limit, retry_limit, retryable_exceptions,
     skip_limit, skippable_exceptions, job_params_json, priority, timeout_minutes)
VALUES
    ('INTEREST_ACCRUAL_DAILY', 'DAILY', 'CHUNK', '0 0 1 * * ?', TRUE, TRUE,
     1000, 8, 8, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     50, '["java.lang.IllegalStateException"]', '{}', 10, 120),

    ('PENAL_INTEREST_CALCULATION_DAILY', 'DAILY', 'CHUNK', '0 5 1 * * ?', TRUE, TRUE,
     500, 4, 4, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     50, '["java.lang.IllegalStateException"]', '{"npaDpdThreshold": 90}', 20, 90),

    ('DPD_UPDATE_DAILY', 'DAILY', 'CHUNK', '0 10 1 * * ?', TRUE, TRUE,
     1000, 8, 8, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     50, '["java.lang.IllegalStateException"]', '{}', 30, 90),

    ('NPA_CLASSIFICATION_CHECK_DAILY', 'DAILY', 'CHUNK', '0 15 1 * * ?', TRUE, TRUE,
     1000, 8, 8, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     50, '["java.lang.IllegalStateException"]', '{}', 40, 90),

    ('AUTO_DEBIT_PROCESSING_DAILY', 'DAILY', 'CHUNK', '0 20 1 * * ?', TRUE, TRUE,
     500, 6, 6, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     30, '["java.lang.IllegalStateException"]', '{}', 50, 90),

    ('BOUNCE_NSF_HANDLING_DAILY', 'DAILY', 'CHUNK', '0 25 1 * * ?', TRUE, TRUE,
     500, 4, 4, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     30, '["java.lang.IllegalStateException"]', '{}', 60, 60),

    ('DISBURSEMENT_SETTLEMENT_DAILY', 'DAILY', 'CHUNK', '0 30 1 * * ?', TRUE, TRUE,
     100, 2, 2, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     20, '["java.lang.IllegalStateException"]', '{}', 70, 60),

    ('GL_POSTING_RECONCILIATION_DAILY', 'DAILY', 'TASKLET', '0 35 1 * * ?', TRUE, TRUE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 80, 60),

    ('LIMIT_UTILIZATION_REFRESH_DAILY', 'DAILY', 'CHUNK', '0 40 1 * * ?', TRUE, TRUE,
     1000, 4, 4, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     30, '["java.lang.IllegalStateException"]', '{}', 90, 60),

    ('CHARGES_FEE_APPLICATION_DAILY', 'DAILY', 'CHUNK', '0 45 1 * * ?', TRUE, TRUE,
     500, 4, 4, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     30, '["java.lang.IllegalStateException"]', '{}', 100, 60),

    ('SOD_EOD_BALANCING_DAILY', 'DAILY', 'TASKLET', '0 50 1 * * ?', TRUE, TRUE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 110, 30),

    ('STATEMENT_NOTIFICATION_TRIGGER_DAILY', 'DAILY', 'CHUNK', '0 55 1 * * ?', TRUE, TRUE,
     1000, 4, 4, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     50, '["java.lang.IllegalStateException"]', '{}', 120, 60),

    ('RATE_RESET_CHECK_DAILY', 'DAILY', 'CHUNK', '0 0 2 * * ?', TRUE, TRUE,
     500, 4, 4, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     30, '["java.lang.IllegalStateException"]', '{}', 130, 60);

-- =================================================================================================
-- MONTHLY (11) — priority 200-300
-- =================================================================================================
INSERT INTO batch.batch_job_config
    (job_name, job_category, job_type, cron_expression, enabled, restartable,
     chunk_size, concurrency, throttle_limit, retry_limit, retryable_exceptions,
     skip_limit, skippable_exceptions, job_params_json, priority, timeout_minutes)
VALUES
    ('EMI_BILLING_GENERATION_MONTHLY', 'MONTHLY', 'CHUNK', '0 0 2 1 * ?', TRUE, TRUE,
     1000, 8, 8, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     50, '["java.lang.IllegalStateException"]', '{}', 200, 120),

    ('INTEREST_CAPITALIZATION_MONTHLY', 'MONTHLY', 'CHUNK', '0 30 2 1 * ?', TRUE, TRUE,
     1000, 6, 6, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     50, '["java.lang.IllegalStateException"]', '{}', 210, 120),

    ('PROVISIONING_CALCULATION_MONTHLY', 'MONTHLY', 'CHUNK', '0 0 3 1 * ?', TRUE, TRUE,
     500, 4, 4, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     30, '["java.lang.IllegalStateException"]', '{}', 220, 90),

    ('RESTRUCTURED_LOAN_MONITORING_MONTHLY', 'MONTHLY', 'CHUNK', '0 30 3 1 * ?', TRUE, TRUE,
     100, 2, 2, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     20, '["java.lang.IllegalStateException"]', '{}', 230, 60),

    ('RATE_RESET_MONTHLY', 'MONTHLY', 'CHUNK', '0 0 4 1 * ?', TRUE, TRUE,
     500, 4, 4, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     30, '["java.lang.IllegalStateException"]', '{"benchmarkRateRef": "MCLR_1Y"}', 240, 60),

    ('LOAN_AMORTIZATION_SCHEDULE_REFRESH_MONTHLY', 'MONTHLY', 'CHUNK', '0 30 4 1 * ?', TRUE, TRUE,
     1000, 6, 6, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     50, '["java.lang.IllegalStateException"]', '{}', 250, 120),

    ('DORMANT_INACTIVE_ACCOUNT_REVIEW_MONTHLY', 'MONTHLY', 'CHUNK', '0 0 5 1 * ?', TRUE, TRUE,
     1000, 4, 4, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     30, '["java.lang.IllegalStateException"]', '{"dormancyThresholdDays": 180}', 260, 90),

    ('MONTH_END_GL_CLOSE_MONTHLY', 'MONTHLY', 'TASKLET', '0 0 6 1 * ?', TRUE, TRUE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 270, 60),

    ('CREDIT_BUREAU_REPORTING_EXTRACT_MONTHLY', 'MONTHLY', 'TASKLET', '0 0 2 2 * ?', TRUE, TRUE,
     NULL, NULL, NULL, 2, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 280, 90),

    ('REGULATORY_REPORTING_MONTHLY', 'MONTHLY', 'TASKLET', '0 0 3 2 * ?', TRUE, TRUE,
     NULL, NULL, NULL, 2, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 290, 90),

    ('FEE_TRUE_UP_PARTNER_RECONCILIATION_MONTHLY', 'MONTHLY', 'TASKLET', '0 0 2 3 * ?', TRUE, TRUE,
     NULL, NULL, NULL, 2, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 300, 60);

-- =================================================================================================
-- PERIODIC (12) — priority 400-430 (quarterly/scan jobs), 500-520 (annual), 600-640 (event-triggered)
-- =================================================================================================
INSERT INTO batch.batch_job_config
    (job_name, job_category, job_type, cron_expression, enabled, restartable,
     chunk_size, concurrency, throttle_limit, retry_limit, retryable_exceptions,
     skip_limit, skippable_exceptions, job_params_json, priority, timeout_minutes)
VALUES
    -- Quarterly, 1st day of each fiscal-quarter month at staggered hours.
    ('QUARTERLY_NPA_PROVISIONING_REVIEW', 'PERIODIC', 'TASKLET', '0 0 6 1 1,4,7,10 ?', TRUE, TRUE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 400, 90),

    ('QUARTERLY_STRESS_TESTING', 'PERIODIC', 'TASKLET', '0 30 6 1 1,4,7,10 ?', TRUE, TRUE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{"rateShockBps": 200, "defaultRateMultiplier": 1.5}', 410, 120),

    ('QUARTERLY_RATE_RESET', 'PERIODIC', 'CHUNK', '0 0 7 1 1,4,7,10 ?', TRUE, TRUE,
     500, 4, 4, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     30, '["java.lang.IllegalStateException"]', '{"benchmarkRateRef": "TBILL_91D"}', 420, 60),

    -- Scanned quarterly even though the underlying documents/policies renew annually, so nothing
    -- inside the reminder window is missed between runs.
    ('DOCUMENT_EXPIRY_REMINDER', 'PERIODIC', 'CHUNK', '0 0 8 1 1,4,7,10 ?', TRUE, TRUE,
     1000, 4, 4, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     50, '["java.lang.IllegalStateException"]', '{"reminderWindowDays": 45}', 430, 90),

    -- Annual, tied to the April 1 fiscal-year start (India FY: Apr-Mar).
    ('ANNUAL_STATEMENT_FORM16A_GENERATION', 'PERIODIC', 'CHUNK', '0 0 7 1 4 ?', TRUE, TRUE,
     1000, 6, 6, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     50, '["java.lang.IllegalStateException"]', '{}', 500, 120),

    -- Scanned monthly (not literally annually) so any policy expiring within the renewal window
    -- is always caught with time to act; each account's own policy still only renews once a year.
    ('ANNUAL_INSURANCE_RENEWAL_CHECK', 'PERIODIC', 'CHUNK', '0 30 7 1 * ?', TRUE, TRUE,
     500, 4, 4, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     30, '["java.lang.IllegalStateException"]', '{"renewalWindowDays": 60}', 510, 60),

    ('ANNUAL_CREDIT_LIMIT_REVIEW', 'PERIODIC', 'CHUNK', '0 0 8 1 1 ?', TRUE, TRUE,
     500, 4, 4, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     30, '["java.lang.IllegalStateException"]', '{}', 520, 90),

    -- Event-triggered: cron_expression is NULL and the job never fires on a Quartz schedule; it
    -- runs only via POST /api/v1/batch-admin/jobs/{jobName}/trigger with the triggering context
    -- (e.g. accountId) passed in job_params_json at trigger time. restartable=false so every
    -- trigger gets its own fresh JobInstance (a UUID runId) rather than resuming a prior attempt.
    ('LOAN_CLOSURE_NOC_GENERATION', 'EVENT_TRIGGERED', 'TASKLET', NULL, TRUE, FALSE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 600, 30),

    ('FORECLOSURE_PREPAYMENT_PROCESSING', 'EVENT_TRIGGERED', 'TASKLET', NULL, TRUE, FALSE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 610, 30),

    ('WRITE_OFF_PROCESSING', 'EVENT_TRIGGERED', 'TASKLET', NULL, TRUE, FALSE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 620, 30),

    ('SETTLEMENT_OTS_PROCESSING', 'EVENT_TRIGGERED', 'TASKLET', NULL, TRUE, FALSE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 630, 30),

    ('LOAN_RESTRUCTURING_BATCH', 'EVENT_TRIGGERED', 'TASKLET', NULL, TRUE, FALSE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 640, 30);
