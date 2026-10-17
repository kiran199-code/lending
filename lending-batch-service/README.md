# lending-batch-service

Standalone Spring Batch application hosting every daily, monthly, and periodic batch job for
the lending platform. Deployed as its own JAR, independently of `lending-service` (the
account-servicing web app) — it shares the same PostgreSQL database and reads/writes
`lending-service`'s business tables (`loan_account`, `amortization_schedule`,
`interest_accrual`, `emi_transaction`, `ledger_outbox`, `mortgage`, ...), but keeps all of its
own framework/metadata tables in a dedicated `batch` schema so the two deployables never
collide on DDL or migration ownership.

## Why a separate service

Batch load (partitioned scans across the whole loan book) has a very different resource and
availability profile than the servicing API — it shouldn't compete with request traffic for
connection-pool headroom, and it shouldn't be redeployed every time an API endpoint changes.
Running it as its own process also means it can be scaled, clustered (via Quartz's JDBC job
store, `isClustered=true`), and restarted independently.

## Architecture

- **Config-driven job registry** (`core/BatchJobRegistry`): every job is one row in
  `batch.batch_job_config` plus one Spring bean implementing `BusinessChunkJob<I,O>` (partitioned,
  account-range scans) or `BusinessBatchTasklet` (single-operation jobs — reconciliation, GL
  close, event-triggered actions). The registry assembles a Spring Batch `Job` for every config
  row at startup and on `POST /api/v1/batch-admin/config/reload` — no per-job Java wiring is
  needed beyond the business class itself.
- **Partitioning**: `ColumnRangePartitioner` splits the account_id range for a job's
  `concurrency` (grid size); `TaskExecutorPartitionHandler` runs the partitions on a
  per-job `ThreadPoolTaskExecutor` sized from `throttle_limit` (independent of grid size, so you
  can fan a job out into 8 partitions but still cap it to 4 concurrent DB connections).
- **Scheduling**: `core/quartz/QuartzJobBridge` reads every `enabled` config row with a
  cron_expression and (re)schedules it in Quartz on startup and on config reload.
  `job_category = EVENT_TRIGGERED` rows have `cron_expression = NULL` and are never scheduled —
  they only run via the manual trigger endpoint (see "Event-triggered jobs" below). They are
  still first-class rows in the same table and go through the same registry; there's no
  special-cased code path for them.
- **Cross-cutting concerns** are shared listeners, not per-job code: `MetricsJobExecutionListener`
  (Micrometer counters/timers + a `JobCompletionEvent` published to `KafkaJobEventBridge`),
  `AuditStepExecutionListener` (writes `batch.batch_job_audit_log`), `DeadLetterSkipListener`
  (writes `batch.batch_job_error_records` for every skipped/failed item),
  `BatchJobContextStepListener` (makes `businessDate`/`job_params_json` available to
  processor/tasklet code via a `ThreadLocal`, correctly scoped per partition worker thread).
- **Idempotency & restart**: before every launch, `BatchJobLauncherService` checks
  `JobExplorer.findRunningJobExecutions(jobName)` and refuses to double-launch. Jobs with
  `restartable = true` use just `businessDate` as their JobParameters key, so a failed run can be
  resumed by re-triggering with the same date; `restartable = false` jobs (the event-triggered
  ones) get a random UUID `runId` so every trigger is a fresh, independent `JobInstance`.

## Running locally

Prerequisites: PostgreSQL 16 reachable at `localhost:5432` with a `lending` database already
containing the `lending-service` schema (this app only owns the `batch` schema — see
`db/migration`), and, optionally, a local Kafka broker at `localhost:9092` (job-completion
events are best-effort; a missing broker will not fail a batch job, see `KafkaJobEventBridge`). Swagger UI: `/swagger-ui.html` (login `batch-admin`; `BATCH_SWAGGER_ENABLED=false` disables it; default on, except off in the `prod` profile). Publishing can be switched off with `BATCH_KAFKA_ENABLED=false` (`batch.kafka.enabled`; the default is `false` in the `local` profile, `true` in `prod`, and docker-compose and the integration test turn it on): no producer is created and no broker connection is attempted.

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Flyway runs automatically at startup: `V1__batch_framework_schema.sql` creates the `batch`
schema and the three framework tables, `V2__seed_batch_job_config.sql` seeds one row per
business job, `V3__quartz_schema.sql` creates Quartz's `QRTZ_*` tables. Spring Batch's own
`BATCH_*` metadata tables are created by Boot's batch schema initializer
(`spring.batch.jdbc.initialize-schema=always`) into the same schema via the JDBC URL's
`currentSchema=batch,public`.

For production, see `application-prod.yml` — every connection/credential value there comes from
an environment variable, and the same JAR is meant to run as multiple clustered instances
against one database.

## Admin/ops API

All under `/api/v1/batch-admin`, secured with HTTP Basic (`batch.admin.username` /
`batch.admin.password`, role `BATCH_ADMIN`):

| Method & path | Purpose |
|---|---|
| `GET /jobs` | List every configured job and its current settings. |
| `GET /jobs/{jobName}` | One job's config. |
| `POST /jobs/{jobName}/trigger` | Launch a job now (see "Triggering runs" below). |
| `GET /jobs/{jobName}/executions?limit=` | Recent `JobExecution`s for a job. |
| `GET /jobs/{jobName}/errors?limit=` | Recent dead-letter records for a job. |
| `GET /executions/{jobExecutionId}/errors?limit=` | Dead-letter records for one specific run. |
| `POST /config/reload` | Re-read `batch_job_config` and rebuild + reschedule every job — use after editing config rows. |
| `GET /status/today` | Per-job status: last execution outcome + next Quartz fire time. |
| `POST /eod/run` | Run end of day for the current business date (or resume a failed one): 202 started, 409 if already running. |
| `GET /eod/status` | `{running, businessDate, progress, lastRun}` - `progress` is live while a run is going (state IDLE / RUNNING / ADVANCED / FAILED, current job, done / total, one row per daily job with its status and times); `lastRun` lists each job's result and whether the date advanced. Progress is kept in memory and resets when the service restarts. The admin console's End of day screen polls this. |

---

## Adding a new job — checklist

1. **Write the business class** under `jobs/{daily,monthly,periodic}` (pick the package by
   cadence, it's organizational only — the framework doesn't read package names):
   - Per-account scan over a range → implement `BusinessChunkJob<I,O>`, typically by extending
     `AbstractAccountRangeChunkJob<Long>` and overriding `accountFilter()` (a SQL `WHERE`
     fragment against `loan_account`) and `processor()`. Override `writer()` too if the job needs
     to persist something beyond what a custom processor already does.
   - Single book-wide operation (reconciliation, GL close, an event-triggered action) → implement
     `BusinessBatchTasklet` and return a `Tasklet` from `buildTasklet()`.
   - `getJobName()` must return the exact string you'll use as `job_name` in step 2 — the
     registry looks jobs up by this string and throws if a config row references a name with no
     matching bean.
   - Annotate the class `@Component` so the registry can find it as a Spring bean.
2. **Insert a `batch_job_config` row** (a new Flyway migration, e.g.
   `V4__add_my_new_job.sql` — never edit `V2__seed_batch_job_config.sql` once it's shipped to any
   environment, Flyway checksums migrations). Set `job_category`, `job_type` to match what you
   built, a cron expression (or `NULL` + `job_category = 'EVENT_TRIGGERED'`), `chunk_size` /
   `concurrency` / `throttle_limit` for `CHUNK` jobs (leave `NULL` for `TASKLET`), and reasonable
   `retry_limit` / `skip_limit` / `timeout_minutes`.
3. **Deploy and call `POST /api/v1/batch-admin/config/reload`** (or just restart — the registry
   builds from the DB on every startup too) to pick up the new job without waiting for the next
   deploy cycle if you inserted the config row directly rather than through a migration.
4. Confirm it shows up in `GET /api/v1/batch-admin/jobs` and, if scheduled, has a sensible
   `nextFireTime` in `GET /api/v1/batch-admin/status/today`.

## Streams per job (2 by default)

Every chunk job runs in **2 streams**: `concurrency` = 2 partitions side by side, on a pool of `throttle_limit` = 2 threads (migration V5 sets this for
all `CHUNK` jobs; a chunk job with an empty `concurrency` also falls back to 2). The number of streams is a per-job setting in `batch.batch_job_config`:
change `concurrency` (and `throttle_limit`) for one job from the admin portal's Configuration screen or `PUT /api/v1/batch-admin/jobs/{jobName}/config`;
it applies from that job's next run. Each partition logs its account-id range and its read/written/skipped counts (`Step ... reads account ids a .. b`,
`Step ... finished: read=.. written=.. skipped=..`).

## Changing frequency / concurrency / commit interval

All of this is a `batch_job_config` row update — no redeploy needed, just an `UPDATE` (bump
`version` is handled automatically via `@Version`) followed by `POST /config/reload`:

- **Frequency**: update `cron_expression`. `QuartzJobBridge` rebuilds the trigger on reload.
- **Concurrency / partition fan-out**: update `concurrency` (the partition grid size).
- **Thread-pool cap**: update `throttle_limit` — this is deliberately independent of
  `concurrency`, so you can partition a job into 8 ranges but still bound it to 4 concurrent DB
  connections via a 4-thread pool.
- **Commit interval**: `chunk_size` on the config row is what `BatchJobRegistry.buildChunkJob`
  passes as the chunk-oriented step's commit interval — larger values commit less often (less
  transaction overhead, larger blast radius per failure), smaller values commit more often.
- **Timeout**: `timeout_minutes` — enforced by `TimeoutTaskletDecorator` for `TASKLET` jobs;
  for `CHUNK` jobs, size `timeout_minutes` generously since Spring Batch itself doesn't have a
  per-step wall-clock timeout, only per-item throughput.

Reload is always required after a direct DB edit — the registry only re-reads config on startup
or on that endpoint call, deliberately, so a config change doesn't affect an in-flight run.

## Triggering ad-hoc / backdated runs

`POST /api/v1/batch-admin/jobs/{jobName}/trigger` with an optional body:

```json
{ "businessDate": "2026-08-20" }
```

Omit `businessDate` to run for the platform's current **business date** (see below), not the calendar date. This is the path for:

- **Backdated EOD catch-up**: if a scheduled run was missed or needs to be redone for a prior
  date, trigger with that `businessDate` explicitly — every business job reads its operating
  date from `BatchJobContext.businessDate()`, never from `LocalDate.now()`, specifically so this
  works uniformly across every job without per-job catch-up code.
- **Event-triggered jobs** (`LOAN_CLOSURE_NOC_GENERATION`, `FORECLOSURE_PREPAYMENT_PROCESSING`,
  `WRITE_OFF_PROCESSING`, `SETTLEMENT_OTS_PROCESSING`, `LOAN_RESTRUCTURING_BATCH`): these have no
  cron schedule at all — triggering is the *only* way they run. Pass whatever the job needs
  (e.g. the specific `accountId`) as additional keys in the trigger request; the framework
  stores it as that run's `job_params_json` and it's readable from
  `BatchJobContext.jobParamsJson()` inside the job's processor/tasklet.
- Every trigger goes through the same idempotency guard as scheduled runs
  (`JobExplorer.findRunningJobExecutions`) — you can't accidentally double-launch a job that's
  already mid-run.

## Error records: surfacing and replay

Every skipped/failed item in a `CHUNK` job (up to `skip_limit`) lands in
`batch.batch_job_error_records` via `DeadLetterSkipListener`, with the failing record's
identifier, a JSON snapshot of the payload, the exception class/message, and a truncated stack
trace. `TASKLET` jobs are expected to write their own error rows the same way from within a
`catch` block if they want per-item dead-lettering (a tasklet failure that isn't caught simply
fails the whole step).

- **Surfacing**: `GET /api/v1/batch-admin/jobs/{jobName}/errors?limit=` (by job) or
  `GET /api/v1/batch-admin/executions/{jobExecutionId}/errors?limit=` (by specific run). Every
  row starts `replay_status = PENDING_REVIEW`.
- **Replay**: there is no automated replay endpoint in this pass (deliberately — replay usually
  needs a human decision about whether the underlying condition was fixed, not a mechanical
  retry). The intended workflow is: review the row, fix the underlying data/condition if needed,
  then either (a) re-trigger the owning job for the same `business_date` (safe for `restartable`
  jobs — the specific account will simply reprocess cleanly this time) or (b) handle it
  out-of-band and mark the row `REPLAYED` / `IGNORED` directly. Wiring a `POST
  /errors/{errorId}/replay` endpoint that re-drives a single record through the job's processor
  is a natural next increment once a job's real business logic (replacing the current `// TODO`
  stubs) makes "replay one record" well-defined for that job.

## Business date and end of day

lending-service owns the business date; this service reads it (`BusinessDateProvider`, `batch.business-date.source=LENDING`; `SYSTEM` = wall clock, standalone
use only). `BatchJobContext.businessDate()` throws if no date was set - there is no silent fallback to the system clock. A cron or manual launch without a date uses the business date.

**End of day** (`core/eod`): `EndOfDayService` asks lending-service to start end of day (`OPEN` -> `EOD_RUNNING`; if it is already `EOD_RUNNING` this is a resume), runs every
enabled `DAILY` job for that date in priority order, skipping any that already completed for it, stops at the first job that does not complete, and only if all did
calls `advance` so the date moves to the next day. It runs nightly (`batch.eod.cron`, default `0 0 1 * * ?`, zone `batch.eod.zone`) from `EndOfDayScheduler`, and on demand
via `POST /api/v1/batch-admin/eod/run` (background thread; poll `GET /eod/status`). One run at a time per instance; across instances lending-service refuses a second start and Spring Batch refuses
a second concurrent run of the same job. With `batch.eod.enabled=true` (default) the `DAILY` jobs' own crons are ignored (`launchScheduled` returns without launching); with `false` they run on their own
crons as before. `MONTHLY`/`PERIODIC` jobs are not part of the chain and still take the business date at the moment their cron fires. Manual triggers (`/jobs/{job}/trigger`) still work for any job; calls that carry a date after the business date are rejected by lending-service.

| Setting (env) | Default | |
|---|---|---|
| `batch.business-date.source` (`BATCH_BUSINESS_DATE_SOURCE`) | `LENDING` | `LENDING` or `SYSTEM` |
| `batch.eod.enabled` (`BATCH_EOD_ENABLED`) | `true` | nightly end of day replaces the DAILY jobs' own crons |
| `batch.eod.cron` (`BATCH_EOD_CRON`) | `0 0 1 * * ?` | when the nightly run starts |
| `batch.eod.zone` (`BATCH_EOD_ZONE`) | `Asia/Kolkata` | zone for the cron |

If a night fails the business date stays on that day in `EOD_RUNNING`; payments for that day can still be inspected, nothing is posted for the next day. Fix the cause (see `GET /jobs/{job}/errors`)
and run `POST /eod/run` again. On the Render free tier the service sleeps, so the nightly cron may be missed - run it on demand.

## Calling lending-service

Batch jobs do not write loan data. The batch database role is read-only on lending's tables and `lending/LendingServiceClient` (JDK `HttpClient` + Jackson) calls lending-service's REST API:
`lending.service.base-url` (`LENDING_SERVICE_URL`, required in the `prod` profile, default `http://localhost:8080` elsewhere), optional Basic auth (`LENDING_SERVICE_USERNAME`/`PASSWORD`,
sent only when a username is set - lending-service has no auth today), connect and request timeouts. It retries only on I/O errors and HTTP 429/502/503/504. Any other failure throws
`LendingServiceException` (an `IllegalStateException`), so the existing skip, retry and dead-letter handling applies per account.

## Business logic status

Eleven of the 37 jobs call lending-service; the other 26 are structurally complete (correct `job_name`, category, type, partitioning and framework wiring) but still stubs with
`// TODO: business logic` markers and Javadoc describing what each needs to do.

| Job | Calls |
|---|---|
| `DEMAND_RAISE_DAILY` | `POST /api/v1/loan-accounts/{id}/demands?businessDate=` (idempotent). Runs first in the daily chain (priority 5, seeded by V6): raises the principal + interest demand for every instalment due on or before the business date, back-fills accounts that pre-date demands, and covers ACTIVE and NPA accounts |
| `PENAL_INTEREST_CALCULATION_DAILY` | `POST /api/v1/loan-accounts/{id}/penal-interest?businessDate=` (idempotent, catches up missed days). Penal rate, grace days and cap come from the loan product (Products screen); `job_params_json` is not used. Priority 20, after `DEMAND_RAISE_DAILY` |
| `INTEREST_ACCRUAL_DAILY` | `POST /api/v1/loan-accounts/{id}/interest-accruals?businessDate=` (idempotent) |
| `DPD_UPDATE_DAILY`, `NPA_CLASSIFICATION_CHECK_DAILY` | `POST /api/v1/loan-accounts/{id}/delinquency-classification` (both use the same endpoint; lending-service now stores `days_past_due` on the account, the bands are still fixed) |
| `RATE_RESET_CHECK_DAILY`, `RATE_RESET_MONTHLY`, `QUARTERLY_RATE_RESET` | `POST /api/v1/loan-accounts/{id}/rate-reset`; rate from the `benchmark_rate` table. `job_params_json` must contain `benchmarkRateRef` (migration V4 seeds `OVERNIGHT_MIBOR` for the daily job) |
| `ACCOUNTING_RECONCILIATION_DAILY` | `POST /api/v1/accounting/reconciliation/run?businessDate=`. Priority 140 (seeded by V7), after every job that can post a journal. Compares the general ledger with the loan tables account by account and fails, naming the failed checks, if anything differs or an exception that gates end of day is open; end of day then stops on that date. A pass with nothing compared if accounting is switched off in lending-service |
| `ACCOUNTING_BALANCE_SNAPSHOT_DAILY` | `POST /api/v1/accounting/snapshot?businessDate=` (rewrites the date's rows, so a re-run is harmless). Priority 150, last in the daily chain |
| `GL_POSTING_RECONCILIATION_DAILY` | `ledger-outbox/push` until `ledger-outbox/pending` is empty; fails if entries stay PENDING (no trial-balance comparison - the ledger gateway is a stub) |
| `MONTH_END_GL_CLOSE_MONTHLY` | fails if any outbox entry is PENDING (partial: no sub-ledger reconciliation or period lock) |
| `FORECLOSURE_PREPAYMENT_PROCESSING` | `POST /{id}/payments/foreclosure`, key `FORECLOSURE-<accountId>-<date>`; params `accountId`, optional `foreclosureDate`, `channel` |
