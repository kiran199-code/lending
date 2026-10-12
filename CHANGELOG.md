# Changelog

## [Unreleased]

### Added
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
