-- V5: every chunk job runs in 2 streams (partitions side by side) instead of the seeded 4-8.
-- concurrency = number of streams (the partition grid size); throttle_limit = size of the thread pool that
-- runs them. Both stay per-job settings: change them for one job from the admin portal's Configuration
-- screen or with PUT /api/v1/batch-admin/jobs/{jobName}/config (concurrency, throttleLimit); a new value is
-- used from the job's next run. Tasklet jobs are single-threaded and keep NULL.
UPDATE batch.batch_job_config
   SET concurrency = 2,
       throttle_limit = 2
 WHERE job_type = 'CHUNK';
