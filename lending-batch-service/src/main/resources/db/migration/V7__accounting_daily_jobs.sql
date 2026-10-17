-- V7: the two end-of-day accounting jobs. Both are single-threaded tasklets (no chunking, no partitioning) that call
-- lending-service once.
--   ACCOUNTING_RECONCILIATION_DAILY  (priority 140) proves the general ledger agrees with the loan sub-ledger; a failure
--                                    stops end of day on the date, so the business date never moves over a break.
--   ACCOUNTING_BALANCE_SNAPSHOT_DAILY (priority 150) records the day's balance of every GL account, after the proof.
-- They run after every job that can post a journal (the last is CHARGES_FEE_APPLICATION_DAILY at 100). The retry limit is 1:
-- a reconciliation that fails has found a real difference, which running it again does not change.
INSERT INTO batch.batch_job_config
    (job_name, job_category, job_type, cron_expression, enabled, restartable,
     chunk_size, concurrency, throttle_limit, retry_limit, retryable_exceptions,
     skip_limit, skippable_exceptions, job_params_json, priority, timeout_minutes)
VALUES
    ('ACCOUNTING_RECONCILIATION_DAILY', 'DAILY', 'TASKLET', '0 10 2 * * ?', TRUE, TRUE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 140, 30),

    ('ACCOUNTING_BALANCE_SNAPSHOT_DAILY', 'DAILY', 'TASKLET', '0 15 2 * * ?', TRUE, TRUE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 150, 30)
ON CONFLICT (job_name) DO NOTHING;
