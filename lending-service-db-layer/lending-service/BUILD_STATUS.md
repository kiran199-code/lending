# Lending Service — Build Status

_Analysis generated 2026-08-26 from `lending-service-db-layer/lending-service`._

## What this is

A Spring Boot 3.3.2 / Java 21 service for a bank's retail lending book, currently scoped to **Phase 1: loan account creation and servicing** for two products — **Retail Unsecured** loans and **Mortgage-backed** loans. The `pom.xml` description calls this the "DB layer," and that's accurate: this phase is almost entirely persistence, domain modeling, and business-rule services sitting directly on top of the database, with no REST controllers, security layer, or external API surface yet.

Stack: Spring Data JPA, PostgreSQL 16, Flyway (8 migrations, V1–V8), Lombok, Jackson (for a JSONB outbox payload), JUnit 5 + Testcontainers for integration tests (real Postgres, not H2 — deliberately, per a comment in `AbstractIntegrationTest`, because day-count math, JSONB, and partitioning behave differently on H2).

## Module map

The code is organized by domain under `com.bank.lending`:

- **account** — `LoanAccount` entity, repository, `LoanAccountService` (creation, disbursement, floating-rate resets, delinquency classification), account number generation.
- **mortgage** — `Mortgage` and `MortgageValuationHistory` entities, `MortgageService` (mortgage creation, charge registration, valuation updates, LTV calculation).
- **schedule** — `AmortizationScheduleEntry` and `InterestAccrual` entities, `AmortizationScheduleService` (EMI schedule generation/regeneration), `InterestAccrualService` (daily accrual batch), `DayCountCalculator`.
- **payment** — `EmiTransaction` entity, `EmiPaymentService` (EMI posting, part-payments, foreclosure payoff).
- **ledger** — `LedgerOutboxEntry` entity, `LedgerOutboxService` (outbox writer + poller), `LedgerGatewayClient` interface with a `LoggingLedgerGatewayClient` stub implementation.
- **common** — `FinanceMath` (EMI formula), two exception types (`ResourceNotFoundException`, `InvalidAccountStateException`).

## Database schema (Flyway V1–V8)

| Migration | Purpose |
|---|---|
| V1 | `loan_account` — core table for both products. Status enum spans `PENDING_DISBURSEMENT` → `ACTIVE` → NPA buckets (`NPA_SMA0/1/2`, `NPA_SUBSTANDARD`, `NPA_DOUBTFUL`, `NPA_LOSS`) → `CLOSED`/`FORECLOSED`/`WRITTEN_OFF`. Has a CHECK constraint (`chk_unsecured_no_mortgage`) that an UNSECURED account can never carry a `mortgage_id`. |
| V2 | `mortgage` + `mortgage_valuation_history` — property collateral, LTV, charge status, legal verification status. |
| V3 | Adds the FK `loan_account.mortgage_id → mortgage.mortgage_id`, closing a deliberate circular reference (mortgage needs the account to exist first; the FK back the other way is added once mortgage exists). |
| V4 | `amortization_schedule` — one row per installment, versioned (`schedule_version`) so regenerating a schedule (rate reset, part-payment) never deletes history; "current" = max version per account. |
| V5 | `interest_accrual` — **partitioned by month** (`business_date` range partitioning), with bootstrap partitions for 2026-07/08/09. `UNIQUE(account_id, business_date)` is the idempotency guard for the daily batch. |
| V6 | `emi_transaction` — also partitioned by month (`value_date`), same bootstrap window. Unique index on `(account_id, idempotency_key)` where key is non-null, enforcing no-duplicate-posting. |
| V7 | `create_monthly_partition(table, year, month)` plpgsql function, meant to be invoked by a future `@Scheduled` job so partition creation becomes a routine op instead of a migration. |
| V8 | `processed_request` (generic API idempotency table — not yet wired to anything, since there are no controllers) and `ledger_outbox` (outbox pattern table backing the ledger module). |

Both partitioned tables only have partitions through **2026-09** — since today is 2026-08-26, that's about one month of runway left before inserts for October would fail unless the V7 function is invoked (manually, or via a scheduler that doesn't exist yet).

## Business logic implemented

- **Account lifecycle**: create (idempotent on `sanction_ref_id`) → disburse (generates the initial amortization schedule, idempotent if already `ACTIVE`, blocks mortgage disbursement until a mortgage charge is linked) → EMI servicing → foreclosure/closure.
- **EMI math**: standard reducing-balance formula in `FinanceMath` (`EMI = P·r·(1+r)^n / ((1+r)^n − 1)`, monthly rest, falls back to straight-line P/n when rate is zero).
- **Amortization schedule generation/regeneration**: `AmortizationScheduleService` builds the full installment table up front; regeneration bumps `schedule_version` and keeps prior versions for audit rather than mutating them.
- **Floating-rate resets**: `LoanAccountService.applyFloatingRateReset` pages through all ACTIVE floating accounts on a given benchmark, recalculates the rate, and regenerates the remaining schedule — skips accounts whose new rate is unchanged.
- **Daily interest accrual**: `InterestAccrualService.runDailyAccrual` pages through ACTIVE accounts, computes daily interest via `DayCountCalculator` (ACT/365, ACT/ACT, 30/360 conventions), and upserts via a native `INSERT ... ON CONFLICT DO NOTHING` for idempotency.
- **Delinquency classification**: `LoanAccountService.classifyDelinquency` re-derives account status from days-past-due on the oldest unpaid installment, approximating RBI IRAC asset-classification bands (SMA-0/1/2 within 90 DPD, then substandard/doubtful/loss). The code explicitly flags this as a simplified placeholder to revisit with Risk/Compliance.
- **Payments**: `EmiPaymentService` handles three flows, each idempotent on `(account_id, idempotency_key)`:
  - `postEmiPayment` — must match the exact EMI due amount, marks the installment PAID, closes the account if that was the last installment.
  - `postPartPayment` — reduces outstanding principal and regenerates the schedule (same installment count, recalculated EMI; doesn't shorten tenure).
  - `foreclose` — pays off outstanding principal plus interest accrued since the last paid installment, closes the account as `FORECLOSED`.
- **Mortgage servicing**: `MortgageService` handles mortgage creation (idempotent on `account_id`), charge registration (which links `mortgage_id` back onto the loan account, unblocking disbursement), valuation recording (with full history retained), and a query for mortgages due for periodic revaluation.
- **Ledger outbox pattern**: every money-moving/state-changing operation (disbursement, EMI posting, foreclosure) writes a `PENDING` row to `ledger_outbox` in the same transaction. `LedgerOutboxService.pushPendingEntries` is the poller half (not yet wired to a scheduler) that would push to a real core-banking ledger via `LedgerGatewayClient` — currently only a logging stub (`LoggingLedgerGatewayClient`) exists, explicitly marked as a placeholder to replace before production.

## Design patterns worth noting

- **Idempotency everywhere**: sanction ref on account creation, mortgage account_id, EMI transaction idempotency keys, accrual `ON CONFLICT DO NOTHING`, ledger outbox — this is a consistent, deliberate theme throughout the service, appropriate for a system where retried requests (batch reruns, NACH retries, API replays) must not double-post money.
- **Outbox pattern** for ledger integration, so ledger downtime doesn't block loan servicing.
- **Schedule versioning** instead of in-place mutation, preserving a full audit trail of every amortization schedule regeneration.
- **Optimistic locking** (`@Version`) on both `LoanAccount` and `Mortgage`.
- Several in-code comments flag known simplifications for future revisit: the NPA_LOSS >1095-day threshold ("placeholder... revisit with Risk/Compliance"), the 30/360 day-count convention being flattened to a 360-day annual basis rather than a true date-pair convention, and LTV being computed against sanctioned amount rather than current outstanding principal.

## Test coverage

Three integration test classes, all against a real Testcontainers Postgres 16 instance:

- `FlywayMigrationTest` — all migrations apply cleanly, core tables exist, accrual partitions exist for the bootstrap months, the circular FK is established, and the partition-management function works.
- `LoanAccountRepositoryIntegrationTest` — unsecured account creation, duplicate sanction-ref rejection (DB-level idempotency), the mortgage circular-link flow, the `chk_unsecured_no_mortgage` constraint, schedule versioning, accrual idempotent upsert, and EMI transaction idempotency-key uniqueness.
- `AbstractIntegrationTest` — shared Testcontainers/Spring Boot test base.

No unit tests were found for the service classes (`LoanAccountService`, `EmiPaymentService`, `MortgageService`, `AmortizationScheduleService`, `InterestAccrualService`, `FinanceMath`, `DayCountCalculator`) — coverage today is entirely at the repository/schema level via integration tests.

## What's not built yet

- **No API layer** — no REST controllers, no request validation wiring (despite `spring-boot-starter-validation` being a dependency), no security/auth.
- **No scheduler** — the daily accrual batch, floating-rate reset, delinquency classification, ledger outbox poller, and monthly partition creation are all plain `@Transactional` service methods with no `@Scheduled` trigger or job runner wired up yet.
- **No real ledger integration** — `LedgerGatewayClient` has only the logging stub; every outbox entry will sit `PENDING` (and eventually `FAILED` after 5 retries) forever until a real adapter is written.
- **`processed_request` table (V8) is unused** — migrated but no code references it; it looks intended as a generic API-level idempotency cache for future controllers.
- **Partition runway is short** — partitions only exist through September 2026 for both partitioned tables; the creation function exists (V7) but isn't scheduled anywhere yet.
- **No service-level unit tests** — only DB/repository integration tests exist today.

## File inventory

```
lending-service-db-layer/lending-service/
├── pom.xml
├── docker-compose.yml                    (local Postgres 16 for dev)
├── src/main/resources/
│   ├── application.yml                   (ddl-auto: validate — Flyway owns schema)
│   └── db/migration/V1..V8__*.sql
├── src/main/java/com/bank/lending/
│   ├── LendingServiceApplication.java
│   ├── account/       (domain, dto, repository, service, util)
│   ├── mortgage/       (domain, dto, repository, service)
│   ├── schedule/       (domain, repository, service, util)
│   ├── payment/        (domain, repository, service)
│   ├── ledger/         (domain, repository, service)
│   └── common/         (exception, util)
└── src/test/java/com/bank/lending/db/
    ├── AbstractIntegrationTest.java
    ├── FlywayMigrationTest.java
    └── LoanAccountRepositoryIntegrationTest.java
```
