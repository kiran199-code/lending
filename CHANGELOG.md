# Changelog

## [Unreleased]

### Added
- **E2E console: start date field.** The hosted console (`lending-e2e-test`) now has a Start date box next to accounts and max simulated days, sent as `start-date`. Before, the
  console could not change it and the built-in default (2026-11-01) was refused once the platform business date had moved past it. It must be the current business date or later.
- **End of day screen in the admin console** (`/#/eod`): shows the business date and the next one before you start or restart, asks for confirmation, and follows the run
  with a per-job progress table that refreshes itself (3 s while running, 10 s otherwise). Restart resumes a stopped run (finished jobs are skipped). The start call carries
  the date shown on screen and is refused with 409 if the business date has moved on. Admin API: `GET /api/admin/eod/status`, `POST /api/admin/eod/run`.
  Batch service: `GET /api/v1/batch-admin/eod/status` now includes live `progress` (new `EodListener`, `EodProgress`; `EndOfDayService.run(actor, listener)`).
  Tests: `EodProgressTest`, `EodControllerTest`. Needs `BATCH_SERVICE_URL` and `BATCH_ADMIN_PASSWORD` on the admin service.

### Documentation
- **Architecture deck v1.6** (`docs/Lending-Platform-Architecture-v1.6.pptx`, 44 slides) is now organised in seven sections, each opened by a divider slide that lists its slides: Platform, Product, Account, Admin console, Batch, E2E test suite, Reference and roadmap. New slides: how the deck is organised, what changed in v1.6, the admin End of day screen, end of day live progress and safe start (batch), what one end-to-end run does, and the hosted test console with the start date. The Reference and roadmap section also has the findings of run E2E-261013153527-691 (eight capability gaps, D-001 to D-008) and a **Key areas of concern** slide, led by *no payments triggered* in that run (cause not yet established; check `batch-runs.csv` and `audit-trail.csv`), followed by the capability gaps, the ledger stub and batch coverage, missing Maven proof and free-tier hosting. The roadmap slide now mentions the End of day screen and the ledger stub (outbox entries stay FAILED until a real client exists). The Functional Specification and Technical Design Document remain at v1.5; the End of day screen and start date field are described in this changelog, the module READMEs and the deck.
- Functional Specification, Technical Design Document and architecture deck updated to **v1.5** (Maintenance screen and eleven base products, batch framework fixes,
  two streams per job, end of day waiting for each job, Kafka and Swagger switches, test suite using existing products). `docs/Lending_Platform_*.docx` now hold v1.5;
  the previous version is kept as `-1.docx` and in git history.

### Fixed
- **End of day screen sat on "Loading..." while lending-service was asleep.** The batch status call read the business date from lending-service with retries, so a 502 from a
  cold-starting lending-service held the page for a minute or more. The status call now never waits: the date is read in the background and shown once it arrives (the
  screen says so and keeps checking), and the admin service gives the batch service only 20 s for a status check. The "start" call now asks the batch service to compare the
  date on screen with lending-service's own date (`POST /eod/run?expectedBusinessDate=`), refusing with 409 if it moved. Test `EndOfDayCoordinatorTest`.
- **Tasklet jobs with a timeout failed with "No business date in the job context"** (seen on `GL_POSTING_RECONCILIATION_DAILY`, which stopped end of day).
  `TimeoutTaskletDecorator` runs the tasklet on a separate guard thread, but the business date and job params are thread-local and were set on the
  step's thread. The context is now handed over to the guard thread (`BatchJobContext.snapshot()/restore()`). Affects every tasklet job that has
  `timeout_minutes` set. Test `TimeoutTaskletDecoratorTest`.
- **End of day failed outright when lending-service answered 502** (free-tier cold start or redeploy). The business-date calls (read, start end of day,
  advance) retried for only ~3 s. They now retry up to 9 times with a doubling pause capped at 30 s (about 1.5-2 minutes), still only on 429/502/503/504
  and connection errors; per-account calls keep the short retry so a down service does not stall every account.
- **End of day stopped after the first job it launched** (each run completed one job, the next run the next). The batch job launcher is asynchronous, so
  `SpringBatchEodJobGateway` judged the job while it was still STARTING/STARTED and reported it as failed. It now waits until the job reaches a final
  state (`batch.eod.job-timeout-minutes`, default 180; `batch.eod.poll-interval-ms`) and only then starts the next job in priority order, so one run
  works through all the daily jobs and advances the business date. A job that fails, or is still running after the timeout, stops the chain and the
  reason is logged. Test `SpringBatchEodJobGatewayTest`.
- **Interest accrual (and other partitioned jobs) could silently skip accounts.** Same root cause as the `ReaderNotOpenException`: when partitions
  overwrote each other's reader, one partition could read another's id range and its own range was never processed, with no error (the job still
  ended COMPLETED). Fixed by the per-thread reader (below); each partition now also logs its range and counts so a gap is visible.
- **Batch partitioned jobs failed with `ReaderNotOpenException: Reader must be open before it can be read`** (seen on `DPD_UPDATE_DAILY`, which left the
  end-of-day run FAILED). All partitions of a job share one `PartitionRangeItemReader`, and it kept its per-partition delegate reader in a plain field,
  so partitions starting at the same time overwrote each other's reader before it was opened. The delegate is now held per thread (each partition runs
  its whole step on one thread). Regression test `PartitionRangeItemReaderTest`.

### Changed
- **Batch jobs run in 2 streams.** Migration V5 sets `concurrency = 2` and `throttle_limit = 2` for every chunk job (was 4-8), and 2 is the fallback when
  a chunk job has no value. The number of streams stays a per-job setting (`batch_job_config.concurrency`, editable from the admin portal's
  Configuration screen or the batch admin API).
- **Batch service: Swagger UI** at `/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`) for the batch admin API, behind the same HTTP Basic login
  (`batch-admin` / `BATCH_ADMIN_PASSWORD`). Added `springdoc-openapi-starter-webmvc-ui` 2.6.0 and `OpenApiConfig`; `BATCH_SWAGGER_ENABLED` (default on,
  default off in the `prod` profile, set `true` in `render.yaml`) switches it.
- **Batch service: Kafka publishing can be switched off** (`BATCH_KAFKA_ENABLED=false` / `batch.kafka.enabled`). When off, no producer is created and
  `KafkaJobEventBridge` is not loaded, so nothing tries to reach a broker (this removes the endless `localhost:9092 ... could not be established`
  warnings on setups without Kafka). Default `false` in the `local` profile, `true` in `prod`; `render.yaml` sets it to `false`, docker-compose and the
  integration test keep it on.
- **E2E suite no longer stops on missing products or on one failing account.** The plan is fitted to the products that exist
  (`ProductResolver`, `e2e.products.fallback=AUTO|SKIP`): a missing/inactive/mortgage product is replaced by the closest existing active
  unsecured product or the account is skipped, and out-of-range amounts/tenures/rates are moved inside the product's limits. Product trouble is
  now a preflight warning instead of a blocking problem; only unreachable services, disabled jobs, a wrong business date or no usable product
  stop a run. An account that fails to be created or disbursed (`SETUP-01/02/03`) is recorded and the others carry on.

### Added
- **Maintenance: start from scratch.** Two new options in the admin portal (screen **Maintenance**): *Create base products* and *Clear all data*.
  - lending-service: `GET /api/v1/maintenance/status` (whether clearing is enabled, row counts, which base products exist),
    `POST /api/v1/maintenance/clear-data` (body `{"confirm":"RESET"}`) and `POST /api/v1/maintenance/base-products`.
    Clear empties loan accounts, schedules, accruals, EMI transactions, mortgages and valuations, the ledger outbox, the idempotency log and the
    loan product master in one `TRUNCATE ... RESTART IDENTITY` transaction, and keeps the business date, holidays, benchmark rates and the batch
    service's tables. It answers 403 unless `LENDING_RESET_ENABLED=true` (default false), 400 without the confirmation word, 409 while end of
    day is running. Base products are the eleven standard and sample ones from V10 and V11 (PL01, BL01, EL01, GL01, AL01, HL01 and the schedule-type samples TL01, BB01, AB01, SU01, EM01 that the end-to-end scenarios use); existing codes are left unchanged.
    Creating the base products is always allowed.
  - Admin portal: Maintenance screen with row counts, a typed-RESET confirmation, and the two actions; `/api/admin/maintenance/*` forwards to
    lending-service with the signed-in user as `X-Actor`. The portal still never writes to the database itself. `render.yaml` sets
    `LENDING_RESET_ENABLED=true` for the sandbox lending-service.
  - Not built with Maven yet (the frontend type-checks and builds).
- **Business date (task 1).** The platform now has one business date, owned by lending-service, and jobs run for it instead of the calendar date.
  - lending-service: migration V13 (`business_date_state`, `business_date_history`, `non_working_day`); `/api/v1/business-date`
    (`GET` state, `POST /eod/start` OPEN to EOD_RUNNING, `POST /advance` EOD_RUNNING to next day, idempotent on `expectedBusinessDate`,
    `PUT` to set the date forward while OPEN, `GET /history`, holiday calendar `GET|PUT|DELETE /holidays`, `GET /working-day`). State changes
    take a row lock. Weekends come from `business-date.weekend-days`. Seeded with today (India time).
  - Value dates are checked against it: EMI, part-payment and foreclosure dates, accrual, delinquency and rate-reset dates may not be after the
    business date (`business-date.enforce-no-future-dates`, default on). Statements are dated by it. Monthly partitions are created for the
    business date's month when the date advances or is set, and at startup.
  - Batch service: `BusinessDateProvider` (source `LENDING`; `SYSTEM` only for standalone use). `BatchJobContext` no longer falls back to the system
    clock. Cron and manual launches without a date use the business date. New end-of-day run (`EndOfDayService`): starts EOD, runs the enabled
    DAILY jobs in priority order for the business date, stops at the first job that does not complete, and only then advances the date; a re-run
    resumes without repeating finished jobs. Nightly at `batch.eod.cron` (default 01:00 India), and on demand with
    `POST /api/v1/batch-admin/eod/run` (202; poll `GET /api/v1/batch-admin/eod/status`). While `batch.eod.enabled=true` the DAILY jobs' own crons are ignored.
  - Admin portal (task 1c): days past due, overdue and upcoming lists, the dashboard, account figures and the accrual-summary default now use the
    business date, read from `business_date_state` over the existing read-only connection (`BusinessDateSource`; falls back to the wall clock with
    a logged warning if the table is missing). New `GET /api/admin/business-date`; the sidebar shows the date and whether end of day is running.
    No new database grant is needed. This fixes the E2E suite's DPD-04 finding.
  - E2E suite (task 1c): `e2e.business-date.mode=MANAGED` (default) sets the business date to `e2e.start-date` and moves it one day per simulated
    day. `e2e.eod.mode=EOD_RUN` (default) closes each day with the batch service's `POST /batch-admin/eod/run` (polled via `/eod/status`);
    `JOBS_THEN_ADVANCE` triggers the jobs one by one and advances through lending-service (needed for `e2e.accrual.path=API` /
    `e2e.dpd.path=API`). New checks BAT-05, BDT-01, BDT-02, BDT-03; DPD-04 now expects the DPD at the business date. Preflight checks that the start
    date is not before the platform's business date and that the admin and batch services expose the new endpoints. `teardown` forecloses at the
    business date. `mode=OFF` keeps the old behaviour (needs `BUSINESS_DATE_ENFORCE_NO_FUTURE=false`).
  - Not done yet: MONTHLY and PERIODIC jobs still start from their own crons and take the business date at that moment (they are not part of
    the EOD chain, and the date may already have moved on); due dates falling on non-working days are not adjusted.
  - Not compiled or run with Maven here. The state-machine rules, end-of-day run, provider and client were compiled with `javac` and their tests pass (the
    business-date service, entities, controller and the changes to the payment, accrual, account and statement services have only been reviewed).
- **Batch jobs now call lending-service (Phase A).** The batch service gets a `LendingServiceClient` (JDK HTTP client; base URL
  `LENDING_SERVICE_URL`, optional Basic auth, timeouts, retry on I/O errors and 429/502/503/504 only; failures are
  `IllegalStateException`s so the existing skip and dead-letter handling applies). Nine jobs are wired, the other 27 are unchanged stubs:
  - `INTEREST_ACCRUAL_DAILY` -> `POST /api/v1/loan-accounts/{id}/interest-accruals?businessDate=` (new, per account, idempotent).
  - `DPD_UPDATE_DAILY` and `NPA_CLASSIFICATION_CHECK_DAILY` -> `POST /api/v1/loan-accounts/{id}/delinquency-classification` (new, per account).
    Both call the same endpoint for now: lending-service does not persist DPD and its bands are fixed.
  - `RATE_RESET_CHECK_DAILY`, `RATE_RESET_MONTHLY`, `QUARTERLY_RATE_RESET` -> `POST /api/v1/loan-accounts/{id}/rate-reset` (new, per account).
    The rate comes from the new `benchmark_rate` table (migration V12; `PUT /api/v1/benchmark-rates/{ref}/{effectiveDate}`,
    `GET /api/v1/benchmark-rates/{ref}?asOf=`, `GET .../history`). A run without `benchmarkRateRef` in `job_params_json` fails.
    Batch migration V4 gives the seeded daily job `{"benchmarkRateRef": "OVERNIGHT_MIBOR"}` (only while its params are still `{}`).
  - `GL_POSTING_RECONCILIATION_DAILY` -> `ledger-outbox/push` until `ledger-outbox/pending` is empty (fails if entries stay PENDING).
    The GL trial-balance comparison is not done (the ledger gateway is a stub).
  - `MONTH_END_GL_CLOSE_MONTHLY` -> fails if any ledger outbox entry is PENDING (partial: no sub-ledger reconciliation or period lock).
  - `FORECLOSURE_PREPAYMENT_PROCESSING` -> `POST /{id}/payments/foreclosure` with key `FORECLOSURE-<accountId>-<date>`; params `accountId`, optional `foreclosureDate`, `channel`.
  - Existing bulk endpoints (`/interest-accruals/run`, `/delinquency-classifications`, `/rate-resets`) are unchanged.
  - Config: `lending.service.*` in `application.yml`; required in the prod profile. `render.yaml` and `docker-compose.yml` set `LENDING_SERVICE_URL`.
  - Tests: batch `LendingServiceClientTest` and `LendingBackedJobsTest` (23 cases, run against an in-JVM HTTP server and a fake client); lending
    `BenchmarkRateServiceTest` and `AccountLevelBatchOperationsTest`; the batch integration test now stands up a lending-service stub.
  - Not yet compiled or run by Maven here (the build sandbox could not reach Maven Central). The batch client, `JobParams`, job bodies and their unit tests
    were compiled with `javac` against stubs of the Spring types and pass; the lending-service changes and the integration test have only been reviewed.
- `lending-e2e-test-service`: standalone end-to-end lifecycle test suite (Phase 1). Scenario matrix (CSV), simulated business
  calendar, real batch trigger + poll, read-only verification via admin APIs, independent day-count / schedule / DPD checks,
  per-day audit trail, run report, ranked defect list, production guard, run-ID tagging, teardown SQL, optional REST trigger,
  Dockerfile and optional Testcontainers profile. No production code changed; platform gaps are listed in its
  `docs/DISCOVERY_REPORT.md`.

## 2026-10-11 (docs v1.3)

- **Day-count conventions**: ACT/360, 30/360 (US) and 30E/360 added to ACT/365 and ACT/ACT (`DayCountCalculator.yearFraction`; daily accrual uses a 360 basis for the three 360-day conventions).
- **Schedule types**: EMI, EQUAL_PRINCIPAL, INTEREST_ONLY (bullet), BALLOON and STEP_UP_EMI, built by the new pure `ScheduleCalculator`. Interest for each instalment is `outstanding x rate x year fraction` under the account's day count; instalments are sized on the average rate per period and the last instalment clears the balance. (STEP_UP_EMI was added so the step-up % parameter has a schedule to act on.)
- **Schedule parameters** on product (default + maximum) and account: repayment frequency (MONTHLY / QUARTERLY / HALF_YEARLY / YEARLY; products also list the allowed frequencies), moratorium months (interest-only opening instalments), balloon %, step-up % a year. `ScheduleParameterRules` checks that they fit the schedule type and the tenure; `AccountTermsResolver` defaults them from the product and enforces the maximums.
- **Migration V11** widens the day-count and schedule-type CHECKs, adds the columns (existing rows: monthly, 0, 0, 0) and seeds TL01 (equal principal), BB01 (interest only), AB01 (balloon), SU01 (step-up), EM01 (study moratorium). Do not edit V10.
- **API**: account request gains `repaymentFrequency`, `moratoriumMonths`, `balloonPercent`, `stepUpPercent` (old 11-argument form kept in code); product request/response gain the matching defaults, maximums and `allowedRepaymentFrequencies`; account response returns the four values.
- **Rescheduling** (part-payment, floating rate reset) now counts instalments, rebuilds with the account's own type, frequency and day count, continues a running moratorium and the current step-up year, and keeps the original balloon amount.
- **Statement PDF** shows the repayment plan and a headline-instalment label that fits the schedule type.
- **Admin portal**: Products screen has a Repayment column and form fields for schedule type, frequencies, moratorium, balloon and step-up (shown only where they apply); the Configuration screen lists the schedule rules.
- **Docs v1.3**: Technical Design Document section 15.13, Functional Specification section 13.12, README, CLOUD_DEPLOYMENT, architecture deck `docs/Lending-Platform-Architecture-v1.3.pptx` (20 slides, new slide 7).
- Not yet compiled or run by Maven (calculator classes compiled with `javac` and checked directly; V11 run on PostgreSQL 16). After a push, redeploy `lending-service` first (Flyway V11), then admin, then batch.

## 2026-10-11

- **Loan product master (`loan_product`)**: product code, name, account type, rate type, schedule type (EMI), day-count convention, benchmark, default rate and spread, rate / spread / sanctioned amount / tenure bands, ACTIVE / INACTIVE. Migration V10 creates it, seeds PL01, BL01, EL01, GL01, AL01, HL01, creates an open "Legacy product" row for any other code already used by an account, and links every account (`loan_account.product_id`, NOT NULL, FK; new `schedule_type` column).
- **Account creation uses the product**: `POST /api/v1/loan-accounts` (and bulk) look up `productCode` (must exist and be ACTIVE). `accountType`, `rateType`, `interestRate`, `benchmarkRateRef`, `spread` and `dayCountConvention` are now optional and defaulted from the product; supplied values override but must be inside the bands (400 listing every problem); account type and rate type must equal the product's. Unknown product 404, inactive product 409. Existing accounts are not changed when a product is edited.
- **Floating rate reset** is floored and capped at the product's rate band.
- **New API** `/api/v1/loan-products` (list, get, create, replace). Admin portal: new **Products** screen.
- **Edit products in the admin portal**: Products screen has New product and Edit (all parameters, with a note that existing accounts are unchanged). Saved through lending-service (`POST` / `PUT /api/v1/loan-products`, now with `expectedVersion` for edit conflicts, 409); admin still never writes to the database. No event reprices or reschedules all accounts of a product; a product change reaches new accounts, and an individual account only when a floating rate reset reschedules it.
- **Docs v1.2**: Technical Design Document (new section 15.12 plus updates to 15.1, 15.2.4, 15.9, 15.10), Functional Specification (new section 13.11 plus updates to 13.1, 13.2, 13.8, 13.9), README, CLOUD_DEPLOYMENT, architecture deck (new file `docs/Lending-Platform-Architecture-v1.2.pptx`, 19 slides, adds a loan products slide; the earlier 18-slide file was open in PowerPoint and is left as it was).
- `samples/bulk-create-100-accounts.json` rewritten to take defaults from the products.

- **Fix: batch service connection pool exhausted on Render** (`Connection is not available ... total=5, active=5`; Quartz `ClusterManager` / `MisfireHandler` errors, "Failed to schedule job", "Quartz-triggered launch failed", Spring Batch "error saving batch meta data"). `render.yaml`: batch `DB_POOL_SIZE` 5 -> 20, `DB_POOL_MIN_IDLE` 1 -> 4, new `QUARTZ_THREAD_COUNT=3`. `application.yml`: `org.quartz.threadPool.threadCount` now `${QUARTZ_THREAD_COUNT:10}` (default unchanged).

## 2026-10-10 (documents v1.1)

- **Added `lending-admin-service`**: read-only admin portal (Spring Boot 3.3 API + React/Vite UI, one Docker image). Account search and inquiry, amortization versions, accruals, payments, mortgage, statement download, upcoming payments, overdue buckets, payments, accruals, ledger outbox, batch jobs. HTTP Basic login from `ADMIN_USERNAME` / `ADMIN_PASSWORD`; database sessions forced read-only. Registered in root `pom.xml`, `render.yaml`, `docker-compose.yml`.
- **Configuration screen (admin portal)**: Batch jobs tab (edit enabled, cron, restartable, chunk size, concurrency, thread limit, retry/skip limits, timeout, order; Save, Save and apply, Apply changes), Service settings tab (all settings of the three services, secrets never shown), Fixed business rules tab. New env vars on the admin service: `BATCH_SERVICE_URL`, `BATCH_ADMIN_USERNAME`, `BATCH_ADMIN_PASSWORD`.
- **lending-batch-service**: new `PUT /api/v1/batch-admin/jobs/{jobName}` (partial update, validated, version-checked) with `BatchJobConfigService`, `UpdateJobConfigRequest` and a controller-scoped exception handler (400 / 409).
- **Statement PDF**: `GET /api/v1/loan-accounts/{id}/statement` and `/by-account-number/{no}/statement` (OpenPDF).
- **Fix: HTTP 409 on accrual / EMI posting** for dates without a partition. Migration V9 (Oct 2026 - Dec 2027) and `PartitionMaintenanceService` (current month + 6 ahead, at startup and 02:30 IST on the 1st and 15th).
- **Scripts**: `scripts/run-accrual.*` (accrue all active accounts), `scripts/create-loan-account.*`.
- **Bulk endpoints**: `POST /api/v1/loan-accounts/bulk/create` and `/bulk/disburse` (max 200, per-item results, idempotent); Swagger examples; `samples/`.
- **Error mapping**: unknown route / method / media type / malformed body return 404 / 405 / 415 / 400 instead of 500.
- **Render blueprint** (`render.yaml`): free Postgres + services, heap caps, configurable DB and Kafka settings. V6 index fix (partition key in unique index). Batch startup fixes (Spring Batch 5, `JobLauncher` bean clash).
- **Docs**: Functional Specification and Technical Design Document updated to v1.1 (new addendum chapters); architecture deck extended to 18 slides (slide 2 now covers all three services); root README added.

## 2026-09-02 (documents v1.0)

- Initial `lending-service` and `lending-batch-service` reactor build; Functional Specification and Technical Design Document v1.0.
