# Changelog

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
