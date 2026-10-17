-- V6: DEMAND_RAISE_DAILY raises the principal + interest demand for every instalment due on or before the
-- business date. It runs first in the daily chain (priority 5, before interest accrual at 10, DPD at 30 and
-- auto-debit at 50) so every later job sees the demand. 2 streams like the other chunk jobs (see V5).
INSERT INTO batch.batch_job_config
    (job_name, job_category, job_type, cron_expression, enabled, restartable,
     chunk_size, concurrency, throttle_limit, retry_limit, retryable_exceptions,
     skip_limit, skippable_exceptions, job_params_json, priority, timeout_minutes)
VALUES
    ('DEMAND_RAISE_DAILY', 'DAILY', 'CHUNK', '0 55 0 * * ?', TRUE, TRUE,
     1000, 2, 2, 3, '["org.springframework.dao.TransientDataAccessException","java.sql.SQLTransientConnectionException"]',
     50, '["java.lang.IllegalStateException"]', '{}', 5, 90)
ON CONFLICT (job_name) DO NOTHING;
