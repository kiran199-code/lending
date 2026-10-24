-- V8: the three daily jobs of the chit service (lending-chit-service). Each is a single-threaded tasklet that makes one
-- call to the chit service for the business date; the chit service does the work group by group.
--   CHIT_DEMAND_RAISE_DAILY (priority 6)    opens due cycles and raises instalments; right after the loan demand run (5)
--   CHIT_LATE_FEE_DAILY     (priority 112)  charges late fees; after every loan job that posts (the last is 100)
--   CHIT_DEFAULT_CHECK_DAILY (priority 114) flags defaulters; after the late fees
-- All three are seeded SWITCHED OFF. The chit service is new and its jobs are not yet proven against the live
-- platform, and a failing job holds end of day, which would hold the loans too. Switch each on from the Configuration
-- screen (or PUT /api/v1/batch-admin/jobs/{name}) once CHIT_SERVICE_URL, CHIT_SERVICE_USERNAME and
-- CHIT_SERVICE_PASSWORD are set on this service and a manual run has succeeded. (A disabled job cannot be launched from
-- batch-admin, so prove the three runs by hand first: the admin console's Chit funds screens have a button for each,
-- or POST /api/v1/chit/batch/demand-run, /late-fee-run and /default-run on the chit service.) The retry limit is low because the
-- chit service already retries calls itself and every job is safe to repeat by hand.
INSERT INTO batch.batch_job_config
    (job_name, job_category, job_type, cron_expression, enabled, restartable,
     chunk_size, concurrency, throttle_limit, retry_limit, retryable_exceptions,
     skip_limit, skippable_exceptions, job_params_json, priority, timeout_minutes)
VALUES
    ('CHIT_DEMAND_RAISE_DAILY', 'DAILY', 'TASKLET', '0 58 0 * * ?', FALSE, TRUE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 6, 30),

    ('CHIT_LATE_FEE_DAILY', 'DAILY', 'TASKLET', '0 20 2 * * ?', FALSE, TRUE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 112, 30),

    ('CHIT_DEFAULT_CHECK_DAILY', 'DAILY', 'TASKLET', '0 25 2 * * ?', FALSE, TRUE,
     NULL, NULL, NULL, 1, '["org.springframework.dao.TransientDataAccessException"]',
     0, NULL, '{}', 114, 30)
ON CONFLICT (job_name) DO NOTHING;
