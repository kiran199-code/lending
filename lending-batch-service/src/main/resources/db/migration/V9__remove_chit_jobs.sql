-- V9: the chit fund is now its own project (chitfund-batch-service runs its jobs), so the three chit job rows that V8 seeded are
-- removed from this service. V8 itself stays as it is: it may already be applied on a running database, and Flyway refuses to start
-- if an applied migration disappears or changes. On a fresh database V8 inserts the rows and this migration deletes them again.
DELETE FROM batch.batch_job_config
WHERE job_name IN ('CHIT_DEMAND_RAISE_DAILY', 'CHIT_LATE_FEE_DAILY', 'CHIT_DEFAULT_CHECK_DAILY');
