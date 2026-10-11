# Changelog

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
