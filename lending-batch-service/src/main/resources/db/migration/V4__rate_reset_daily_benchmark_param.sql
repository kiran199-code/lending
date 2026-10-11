-- V4: the daily rate-reset job now resets only accounts that reference the benchmark named in
-- job_params_json.benchmarkRateRef (it fails without one, so it can never reprice accounts of
-- benchmarks that reset on a monthly/quarterly cycle). Give the seeded daily job its benchmark.
-- Only touches the row while it still has the seed's empty params, so operator edits are kept.
UPDATE batch.batch_job_config
   SET job_params_json = '{"benchmarkRateRef": "OVERNIGHT_MIBOR"}'::jsonb
 WHERE job_name = 'RATE_RESET_CHECK_DAILY'
   AND job_params_json = '{}'::jsonb;
