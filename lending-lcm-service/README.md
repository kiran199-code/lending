# lending-lcm-service

Standalone **Limit and Collateral Management** service for the lending platform. It owns credit facilities (limits),
the reservation / utilisation ledger, collateral, valuations, allocations and drawing power. It is a separate Maven
project, deployed on its own, and shares only the PostgreSQL instance (schema `lcm`, Flyway history table
`lcm_schema_history`). At runtime it never reads or writes lending tables.

Requirements spec: `docs/LCM_Requirements_v0.1.docx` (repository root).

## What is in phases A to C

| Phase | Content |
|---|---|
| A | Facilities and sub-limit hierarchy, maker-checker changes, temporary excess, availability, reserve / confirm / release / repay / close with idempotency, append-only ledger, event feed |
| B | Collateral register, valuations (maker-checker), legal / charge / insurance rules, activation, allocation to facilities, drawing power capping a facility |
| C | Monitoring sweep (reservation expiry, facility expiry, over-limit, drawing-power shortfall, stale valuation, insurance expiry breaches), ledger integrity check, lending bridge, customer portal read models |

Phases D to F (see the spec) are not built: limit review workflow, group / connected-party limits, FX, insurance and
valuation vendor integrations, reporting.

## Run

```
cd lending-lcm-service
mvn spring-boot:run          # needs Postgres; defaults below
```

| Variable | Default | Meaning |
|---|---|---|
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | localhost / 5432 / lending / lending / lending | Same Postgres as lending |
| `LCM_DB_SCHEMA` | `lcm` | Schema owned by this service |
| `PORT` | `8084` | HTTP port |
| `LCM_SERVICE_USERNAME` `LCM_SERVICE_PASSWORD` | `lcm-service` / `changeme` | HTTP Basic service credential. **Change it.** |
| `LCM_ENFORCE` | `true` | `false` makes every drawdown shadow-mode (recorded, never rejected) |
| `LCM_MAKER_CHECKER_ENFORCE` | `true` | Maker and checker must be different `X-Actor` values |
| `LCM_RESERVATION_TTL_MINUTES` / `LCM_RESERVATION_MAX_TTL_MINUTES` | 30 / 1440 | Default and ceiling for reservation lifetime |
| `LCM_SWEEP_CRON` | `0 * * * * *` | Background sweep; `-` disables the timer |

Swagger UI: `/swagger-ui.html`. Health: `/actuator/health`.

## API in one paragraph

49 endpoints under `/api/v1/lcm`: `facilities` (create, search, read, availability, tree, transactions, changes,
excess), `utilisations` (check, reserve, confirm, release, repay, close, positions), `collateral` and `valuations` and
`allocations`, `facilities/{ref}/drawing-power`, `monitoring` (sweep, breaches, integrity), `events` (pull feed),
`bridge/lending` and `portal`. Every endpoint carries its future IAM permission code in its OpenAPI summary; the full
list is in the IAM catalogue file delivered with this module.

* Calls from people (maker-checker, admin) must send `X-Actor`. Calls from lending-service may omit it.
* Every state-changing utilisation call needs an `Idempotency-Key` header. Same key and body returns the first result with
  `replayed=true`; same key with a different body is `409 IDEMPOTENCY_KEY_REUSED`.
* Failures are `{status, code, message}`; callers switch on `code` (for example `LIMIT_EXCEEDED`,
  `DRAWING_POWER_SHORTFALL`, `FACILITY_NOT_ACTIVE`, `MAKER_CHECKER_VIOLATION`).

## Rules in brief

* A drawdown must fit at the facility and at every ancestor. Available = effective limit - consumed - reserved. Effective limit = sanctioned + approved
  temporary excess, capped by drawing power when the facility is drawing-power governed. Consumed is outstanding principal for a revolving facility
  (repayment restores the limit) and cumulative drawn for a non-revolving one.
* Reserve, confirm and release lock the whole facility chain in ascending id order, so concurrent drawdowns cannot both take the last of a limit and chains cannot deadlock.
* Every counter change is also written to the append-only `limit_txn` ledger (one row per level); `GET /monitoring/integrity` proves counters equal the ledger.
* Drawing power = sum over active allocations of `max(0, value x (1 - haircut) - prior charge) x max LTV x allocation %`; a stale valuation, missing insurance
  (where the type requires it) or non-active collateral contributes zero. Haircut, LTV, validity and requirements per collateral type are rows in `collateral_type_config`
  and the seeded values are **placeholders for Credit Policy to confirm**.
* The event feed is a pull API ordered by (transaction id, id) and bounded by the oldest in-flight transaction, so a consumer holding the last cursor never skips an event.

## Loading existing lending data

1. `psql <lending db> -f scripts/bridge_from_lending.sql` writes `sanctions.json`, `positions.json`, `mortgages.json` (read-only).
2. POST them, in that order, to `/api/v1/lcm/bridge/lending/{facilities,positions,collateral}`. Every item is idempotent and reported as CREATED / SKIPPED / FAILED.
3. `psql <lending db> -f scripts/link_lending_accounts.sql` points open loan accounts at their implicit facility `SANC-<sanction_ref_id>` (needs lending migration V19).

## Security

HTTP Basic with one service credential (`config/SecurityConfig`). This is the single swap point for the IAM resource server: validate the JWT, map the
`lcm.*` permission codes to authorities and take the actor from the token subject instead of `X-Actor`. The customer portal back end must pass the
signed-in customer's id to `/portal/customers/{id}/...`, never a value from the browser.

## Tests

* `mvn test`: pure-logic unit tests (availability, valuation, activation rules, helpers) and, with Docker, `LcmApplicationIT` (Flyway, Spring wiring, security, HTTP flow on Postgres 16).
