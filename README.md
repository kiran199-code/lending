# Lending Platform

A retail lending platform: loan accounts, mortgage collateral, amortization, interest accrual, EMI servicing, a ledger outbox, a 36-slot batch platform and a read-only admin portal.

| Module | What it is | Port (local / Render) |
|---|---|---|
| `lending-service-db-layer/lending-service` | Account-servicing REST API (Java 21, Spring Boot, PostgreSQL, Flyway V1-V9) | 8080 / 10000 |
| `lending-batch-service` | Spring Batch + Quartz platform, 36 job slots (bodies still stubs), Kafka producer | 8081 / 10000 |
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

Search accounts and inquire on amortization (every schedule version, CSV export), daily accruals, payments, mortgage and the customer statement PDF; see upcoming payments (7-90 days), overdue instalments with days-past-due buckets, book-wide payments and accruals, ledger-outbox status and batch jobs. A **Configuration** screen lists every setting of the three services and lets you edit batch job settings (enabled, schedule, chunk size, concurrency, retry/skip limits, timeout, order), saved through the batch service. Lending data is read through read-only connections and never written. Details: [`lending-admin-service/README.md`](lending-admin-service/README.md).

## Scripts and samples

| File | Purpose |
|---|---|
| `scripts/run-accrual.sh` / `.ps1` | Accrue interest for all active accounts for a business date (`BASE_URL` required) |
| `scripts/create-loan-account.sh` / `.ps1` | Create one loan account through the API |
| `samples/` | Bulk create / disburse payloads (100 accounts) |

## Documentation

| Document | Content |
|---|---|
| `docs/Lending_Platform_Functional_Specification.docx` | Business rules and API contracts; Chapter 13 covers the admin portal and later changes |
| `docs/Lending_Platform_Technical_Design_Document.docx` | Architecture and design; Chapter 15 covers the admin portal, configuration and later changes; 15.11 lists every setting |
| `docs/Lending-Platform-Architecture.pptx` | 18-slide deck: architecture, data model, flows, status model, admin portal, API catalogue |
| `docs/CLOUD_DEPLOYMENT.md` | AWS target deployment plan; section 13 summarises the Render sandbox |
| `CHANGELOG.md` | What changed and when |

## Known gaps

Lending API has no authentication (the admin portal and batch API use HTTP Basic); batch job bodies and the real ledger client are stubs; free-tier Postgres expires after 30 days; the admin portal's Spring layer has not yet had a first Maven build. See the Technical Design Document, section 15.10.
