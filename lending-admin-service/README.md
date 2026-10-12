# lending-admin-service

Read-only **admin / back-office console** for the lending platform: a Spring Boot (Java 17) API plus a React
(Vite + TypeScript) single-page UI, shipped as **one container**. It is a separate microservice - it does not
change `lending-service` or `lending-batch-service`.

## What it does

| Page | Shows |
|---|---|
| Dashboard | Portfolio KPIs (accounts, outstanding principal, overdue, due in 7 days, month-to-date collections), status mix, accrual and collections trend |
| Maintenance | **Start from scratch** (sandbox / test only): *Create base products* (the six standard products; existing ones untouched) and *Clear all data* (all accounts and their data, and all loan products; typed `RESET` confirmation). Done by lending-service (`/api/v1/maintenance/*`), which refuses the clear unless it runs with `LENDING_RESET_ENABLED=true`; keeps the business date, holidays and benchmark rates |
| Products | Loan product master: default terms, rate / spread / amount / tenure bands, status and number of accounts; **editable**: New product / Edit, saved through lending-service's validated `/api/v1/loan-products` API (needs `LENDING_SERVICE_URL`). A change applies to accounts opened afterwards and to a floating rate reset of an individual account; existing accounts are not rewritten |
| Accounts | Search and filter (account no. / customer id, status, type, product), sortable, paged |
| Account detail | Overview with derived figures; **Amortization** (every schedule version, CSV export); **Accruals** (date range, chart); **Payments** (EMI history, CSV export); **Mortgage** (property, valuation history); **Statement** PDF download |
| Upcoming payments | Instalments falling due in the next 7 / 15 / 30 / 60 / 90 days, by product |
| Overdue | Unpaid instalments past due with days-past-due buckets (1-30, 31-60, 61-90, 90+) |
| Payments | All EMI transactions, filter by status / date / account |
| Accruals | Daily accrual totals, drill into any business date |
| Ledger outbox | Posting status (PENDING / POSTED / FAILED) and retry counts |
| Batch | Job registry and recent executions from the batch schema (empty until the batch service has run) |
| Configuration | **Batch jobs (editable):** enable/disable, cron, chunk size, concurrency, thread limit, retry/skip limits, timeout, order, restartable - saved through the batch service, then applied with a scheduler reload. **Service settings:** every environment setting of the three services (live values for this service, defaults and Render values for the others; secrets never shown). **Fixed business rules:** behaviour coded into the services |

## How it gets data

It connects **directly to the Lending PostgreSQL database, read-only**. The single exception to read-only is the Configuration screen, which does not write to the database itself: it asks `lending-batch-service` to save a job's settings (`PUT /api/v1/batch-admin/jobs/{name}`, validated there) and then to reload (`POST /config/reload`).

* the Hikari pool is `read-only` and every session runs `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`;
* all queries are `SELECT`s (the current amortization schedule is the highest `schedule_version` per account);
* optional extra hardening: run `db/create-readonly-role.sql` and use that `SELECT`-only role.

The only call to another service is the statement PDF, proxied from `lending-service` when `LENDING_SERVICE_URL` is set.

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` or `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `lending` | Lending database |
| `DB_USER`, `DB_PASSWORD` | `lending_app` / `changeme` | DB credentials |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD` | `admin` / `changeme` | **Console login (HTTP Basic) - change in every real environment** |
| `LENDING_SERVICE_URL` | empty | e.g. `https://lending-service-5tqm.onrender.com`; enables the statement download |
| `BATCH_SERVICE_URL` | empty | Public URL of lending-batch-service; with the password below it enables editing batch job settings (without them the Configuration screen is view-only) |
| `BATCH_ADMIN_USERNAME`, `BATCH_ADMIN_PASSWORD` | `batch-admin` / empty | The batch service's own admin login (same values as on that service) |
| `ADMIN_TIMEZONE` | `Asia/Kolkata` | Only a fallback: "today" is the platform business date (below); this zone is used for the wall-clock date if the business date cannot be read |
| `SERVER_PORT` | `8082` | HTTP port |

## Run locally

```bash
# Backend (needs the Lending DB running, e.g. docker compose up postgres lending-service)
cd lending-admin-service
mvn spring-boot:run

# Frontend with hot reload (proxies /api to :8082)
cd frontend && npm install && npm run dev      # http://localhost:5173
```

Or the whole thing: `docker compose up --build lending-admin-service` from the repo root -> http://localhost:8082
(login `admin` / `changeme`). Swagger UI: `/swagger-ui.html`.

`mvn package` alone builds only the API; the Dockerfile builds the UI and bundles it into the jar's `static/`.

## API (all under `/api/admin`, Basic auth)

`GET /me` · `/dashboard` · `/accounts` · `/accounts/{id}` · `/accounts/by-number/{n}` · `/accounts/{id}/schedule?version=` ·
`/accounts/{id}/accruals?from=&to=` · `/accounts/{id}/payments` · `/accounts/{id}/mortgage` · `/accounts/{id}/statement` ·
`/upcoming-payments` · `/overdue` · `/overdue/buckets` · `/payments` · `/accruals/summary` · `/accruals?date=` ·
`/ledger-outbox` · `/batch` · `/config` · `/config/batch-jobs` (GET, `PUT /{job}?apply=`, `POST /apply`). `/actuator/health` is open for health checks.

## Notes

* Free-tier hosts sleep when idle; the first request after a pause is slow.
* The Batch page needs the batch service to have run at least once (its Spring Batch tables are created then).
* Read-only by design: no create / edit / disburse / waive actions. Those would need the Lending REST API behind a proper role model.

## Business date

Every "today" in the portal (days past due, overdue and upcoming lists, dashboard, account figures, the default end of the accrual
summary) is the platform **business date** owned by lending-service, not the wall clock. The portal reads it straight from
`business_date_state` over its read-only connection (`BusinessDateSource`), so it stays read-only and agrees with the batch and
the payment rules. `GET /api/admin/business-date` returns `{businessDate, status, fromLending}` and the sidebar shows it. If the
table cannot be read (lending-service has not applied migration V13 yet) the wall-clock date is used, `fromLending` is `false`, the
sidebar says so, and a warning is logged at most once a minute. The `lending_admin` role needs no new grant: it already has SELECT on
every table in `public`.
