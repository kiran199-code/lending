# Lending Platform

A retail lending platform: loan accounts, mortgage collateral, amortization, interest accrual, EMI servicing, a ledger outbox, a loan product master, a 36-slot batch platform and an admin portal.

| Module | What it is | Port (local / Render) |
|---|---|---|
| `lending-service-db-layer/lending-service` | Account-servicing REST API (Java 21, Spring Boot, PostgreSQL, Flyway V1-V13), owner of the business date | 8080 / 10000 |
| `lending-batch-service` | Spring Batch + Quartz platform, 36 job slots (nine call lending-service, 27 still stubs), nightly end of day, Kafka producer | 8081 / 10000 |
| `lending-admin-service` | Read-only admin console: Spring Boot API + React UI in one container | 8082 / 10000 |
| PostgreSQL 16 | One shared `lending` database (`public` and `batch` schemas) | 5432 |

## Sandbox (Render free tier)

Free services sleep when idle: the first request after a pause takes about a minute.

| What | URL |
|---|---|
| Lending API | https://lending-service-5tqm.onrender.com |
| Lending Swagger UI | https://lending-service-5tqm.onrender.com/swagger-ui.html |
| Admin portal | `https://lending-admin-service-<suffix>.onrender.com` (Render assigns the suffix; copy it from the dashboard). To enable editing on the Configuration screen set `BATCH_SERVICE_URL` (the batch service's public URL) on this service |
| Admin Swagger UI | `https://lending-admin-service-<suffix>.onrender.com/swagger-ui.html` (click Authorize, then use the admin login) |

**Admin login:** user `admin`; password is the generated `ADMIN_PASSWORD` (Render dashboard > lending-admin-service > Environment). Local default: `changeme`.

## Quick start

```bash
# everything locally (Postgres, Kafka, the three services)
docker compose up --build
#   lending-service  http://localhost:8080/swagger-ui.html
#   admin portal     http://localhost:8082   (admin / changeme)
#   batch admin API  http://localhost:8081   (batch-admin / changeme)

# build all Java modules (the admin UI is built by its Dockerfile)
mvn clean package
```

Deploy to Render: **New > Blueprint** on this repo uses `render.yaml` (one free Postgres + three web services). For the statement button in the admin portal, `LENDING_SERVICE_URL` is preset to the Lending sandbox URL.

## Admin portal in one paragraph

Search accounts and inquire on amortization (every schedule version, CSV export), daily accruals, payments, mortgage and the customer statement PDF; see upcoming payments (7-90 days), overdue instalments with days-past-due buckets, book-wide payments and accruals, ledger-outbox status and batch jobs. A **Products** screen shows and edits the loan products (saved through lending-service). A **Maintenance** screen can create the eleven base products and clear all data to start again (done by lending-service, off unless `LENDING_RESET_ENABLED=true`; the sandbox blueprint turns it on). A **Configuration** screen lists every setting of the three services and lets you edit batch job settings (enabled, schedule, chunk size, concurrency, retry/skip limits, timeout, order), saved through the batch service. Lending data is read through read-only connections and never written by the portal itself. Every "today" in it is the platform business date (sidebar shows it). Details: [`lending-admin-service/README.md`](lending-admin-service/README.md).

## Loan products

Every account is opened from a **loan product** (`loan_product`, API `/api/v1/loan-products`, screen **Products** in the admin portal). A product defines account type, rate type, schedule type (EMI, equal principal, interest only, balloon or step-up EMI), day-count convention (ACT/365, ACT/ACT, ACT/360, 30/360, 30E/360), repayment frequency (monthly to yearly, with the frequencies an account may choose), moratorium, balloon % and step-up %, benchmark, default rate and spread, and bands for rate, spread, sanctioned amount and tenure. When an account is created, anything left out is defaulted from the product; anything supplied must stay inside the bands (one 400 listing every problem). Standard products: PL01, BL01, EL01, GL01 (fixed) and AL01, HL01 (floating on REPO); sample schedule products TL01 (equal principal), BB01 (interest only), AB01 (balloon), SU01 (step-up), EM01 (study moratorium). Interest per instalment follows the account's day count and the last instalment clears the balance; a part-payment or rate reset rebuilds the remaining instalments with the same type. A product change applies to accounts opened afterwards and to a floating rate reset of an individual account (the rate band floors and caps the new rate); existing accounts keep their own terms, and nothing reschedules all accounts of a product.

## Business date and end of day

The platform has one **business date**, owned by lending-service (table `business_date_state`, one row; history in `business_date_history`;
holidays in `non_working_day`). Jobs, payments, accruals, statements and the admin portal all work for that date, not for the calendar date.

| | |
|---|---|
| Status | `OPEN` (normal) or `EOD_RUNNING` (end of day started, not finished). `OPEN` -> `EOD_RUNNING` -> `OPEN` on the next calendar day. |
| Value dates | EMI, part-payment and foreclosure dates, accrual, delinquency and rate-reset dates may not be **after** the business date (HTTP 400). Earlier dates are allowed (back-valuing). Switch off with `BUSINESS_DATE_ENFORCE_NO_FUTURE=false` (throwaway environments only). |
| Seed | The first start seeds today's date in India time. |
| Weekends / holidays | `BUSINESS_DATE_WEEKEND_DAYS` (default `SATURDAY,SUNDAY`) and the `non_working_day` table. They are reported (`workingDay`), but the date still advances one calendar day at a time and due dates are not moved off non-working days. |

**Lending API** (`/api/v1/business-date`): `GET` state; `GET /history`; `POST /eod/start`; `POST /advance` with `{"expectedBusinessDate": "..."}`
(idempotent: a repeated advance for a date already closed returns the current state); `PUT` with `{"date": "...", "reason": "..."}` to set the date
(forward only while `OPEN`, unless `BUSINESS_DATE_ALLOW_BACKWARD_SET=true`; accruals already posted for later dates are not undone);
`GET /working-day?date=`; `GET /holidays?from&to`; `PUT|DELETE /holidays/{date}`. The optional `X-Actor` header is recorded. State changes take a row lock.

**End of day** runs in the batch service: it tells lending-service end of day has started, runs every enabled `DAILY` job for the business date in
priority order, stops at the first job that does not complete, and only if all completed moves the date forward. If a job fails the date stays on
that day in `EOD_RUNNING`; fix the cause and run it again. The re-run resumes and does not repeat jobs that already completed for that date. It runs nightly
at `BATCH_EOD_CRON` (default `0 0 1 * * ?`, zone `BATCH_EOD_ZONE`, default Asia/Kolkata) and on demand with `POST /api/v1/batch-admin/eod/run`
(202 started, 409 if one is running; poll `GET /api/v1/batch-admin/eod/status`). While `BATCH_EOD_ENABLED=true` the `DAILY` jobs' own crons are ignored; with `false` they
run on their own crons and take the business date at that moment. `MONTHLY` and `PERIODIC` jobs are not part of the chain and still run from their own crons.

**Operating it:** check `GET /api/v1/business-date` (or the admin portal's sidebar); after a failed night read `GET /api/v1/batch-admin/eod/status`,
fix the job, run `POST /eod/run` again. To start from a chosen date on a new environment, `PUT /api/v1/business-date`. Add bank holidays with
`PUT /api/v1/business-date/holidays/2026-11-14` `{"description": "..."}`.

**Admin portal:** days past due, overdue and upcoming lists, the dashboard and account figures use the business date, read from `business_date_state` over its read-only
connection (wall-clock fallback if the table is missing). **E2E suite:** sets and advances the business date and closes each simulated day with the end-of-day run.

## Batch jobs and lending-service

The batch service no longer writes loan data itself: wired jobs call lending-service's REST API (`LENDING_SERVICE_URL`, one call per account so a
failed account is skipped and dead-lettered without stopping the run). Nine jobs are wired: `INTEREST_ACCRUAL_DAILY`, `DPD_UPDATE_DAILY`,
`NPA_CLASSIFICATION_CHECK_DAILY`, `RATE_RESET_CHECK_DAILY`, `RATE_RESET_MONTHLY`, `QUARTERLY_RATE_RESET`, `GL_POSTING_RECONCILIATION_DAILY`,
`MONTH_END_GL_CLOSE_MONTHLY` and `FORECLOSURE_PREPAYMENT_PROCESSING`. Floating-rate resets read the new rate from the `benchmark_rate` table
(`PUT /api/v1/benchmark-rates/{ref}/{effectiveDate}`). Details are in the CHANGELOG and [`lending-batch-service/README.md`](lending-batch-service/README.md).

## Scripts and samples

| File | Purpose |
|---|---|
| `scripts/run-accrual.sh` / `.ps1` | Accrue interest for all active accounts for a business date (`BASE_URL` required) |
| `scripts/create-loan-account.sh` / `.ps1` | Create one loan account through the API |
| `samples/` | Bulk create / disburse payloads (100 accounts) |

## Documentation

| Document | Content |
|---|---|
| `docs/Lending_Platform_Functional_Specification.docx` | Business rules and API contracts, version 1.5 (same content as `_v1.5.docx`); Chapter 13 covers the admin portal, loan products (13.11), day counts and repayment schedules (13.12), the business date (13.13), end of day (13.14), batch jobs that call lending-service (13.15), the admin portal and test suite on the business date (13.16), the Maintenance screen and eleven base products (13.17), batch changes and fixes (13.18), the test suite using existing products (13.19) and open items (13.20) |
| `docs/Lending_Platform_Technical_Design_Document.docx` | Architecture and design; version 1.5 (same content as `_v1.5.docx`); Chapter 15 covers the admin portal, configuration (15.11 lists every setting), loan products (15.12), schedule types (15.13), the business date (15.14), end of day (15.15), batch jobs calling lending-service (15.16), the admin console and test suite (15.17), the Maintenance API and base products (15.18), batch framework fixes (15.19), the test-suite product resolver (15.20) and open items (15.21) |
| `docs/Lending-Platform-Architecture-v1.5.pptx` | 29-slide deck in a red and black theme: architecture, data model, flows, status model, admin portal, API catalogue, plus Maintenance and base products, batch fixes, test-suite resilience and the daily cycle. `-v1.4.pptx` (25 slides) and `-v1.3.pptx` (teal) are earlier versions |
| `docs/CLOUD_DEPLOYMENT.md` | AWS target deployment plan; section 13 summarises the Render sandbox |
| `CHANGELOG.md` | What changed and when |

## Known gaps

Lending API has no authentication (the admin portal and batch API use HTTP Basic); 27 batch job bodies and the real ledger client are stubs; delinquency (DPD) is recomputed, not stored, and its bands are fixed; MONTHLY/PERIODIC jobs are outside the end-of-day chain; due dates are not moved off non-working days; the Phase A and business-date work has not yet had a Maven build; free-tier Postgres expires after 30 days; the admin portal's Spring layer has not yet had a first Maven build. See the Technical Design Document, section 15.10.

## End-to-end lifecycle test suite

`lending-e2e-test-service/` is a **separate, independently deployed** test project (own pom, jar, Docker image; not in the
reactor above and not a dependency of any module). It drives the real lending API and the real batch service day by day
over a simulated calendar, verifies through the admin portal's read APIs, and writes an audit trail, a run report and a
ranked defect list. It sets and advances the business date and refuses to run against production. See `lending-e2e-test-service/README.md` and
`lending-e2e-test-service/docs/DISCOVERY_REPORT.md`.
