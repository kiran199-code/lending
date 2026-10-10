# lending-admin-service

Read-only **admin / back-office console** for the lending platform: a Spring Boot (Java 17) API plus a React
(Vite + TypeScript) single-page UI, shipped as **one container**. It is a separate microservice - it does not
change `lending-service` or `lending-batch-service`.

## What it does

| Page | Shows |
|---|---|
| Dashboard | Portfolio KPIs (accounts, outstanding principal, overdue, due in 7 days, month-to-date collections), status mix, accrual and collections trend |
| Accounts | Search and filter (account no. / customer id, status, type, product), sortable, paged |
| Account detail | Overview with derived figures; **Amortization** (every schedule version, CSV export); **Accruals** (date range, chart); **Payments** (EMI history, CSV export); **Mortgage** (property, valuation history); **Statement** PDF download |
| Upcoming payments | Instalments falling due in the next 7 / 15 / 30 / 60 / 90 days, by product |
| Overdue | Unpaid instalments past due with days-past-due buckets (1-30, 31-60, 61-90, 90+) |
| Payments | All EMI transactions, filter by status / date / account |
| Accruals | Daily accrual totals, drill into any business date |
| Ledger outbox | Posting status (PENDING / POSTED / FAILED) and retry counts |
| Batch | Job registry and recent executions from the batch schema (empty until the batch service has run) |

## How it gets data

It connects **directly to the Lending PostgreSQL database, read-only**:

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
| `ADMIN_TIMEZONE` | `Asia/Kolkata` | What "today" means for due / overdue |
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
`/ledger-outbox` · `/batch`. `/actuator/health` is open for health checks.

## Notes

* Free-tier hosts sleep when idle; the first request after a pause is slow.
* The Batch page needs the batch service to have run at least once (its Spring Batch tables are created then).
* Read-only by design: no create / edit / disburse / waive actions. Those would need the Lending REST API behind a proper role model.
