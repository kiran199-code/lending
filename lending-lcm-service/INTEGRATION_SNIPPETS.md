# LCM integration snippets (for the shared files I was told not to edit)

Paste these in when you are ready. Nothing here has been applied. Paths are relative to the repository root.
The module is `lending-lcm-service/`; the lending-service module is `lending-service-db-layer/lending-service/`.

## 1. Root `pom.xml`: one line in `<modules>`

```xml
        <module>lending-lcm-service</module>
```

(LCM has its own Spring Boot parent and Java 17, like the batch service. Nothing else in the root pom changes.)

## 2. `render.yaml`: new service, and the switch on lending-service

Add under `services:` (after `lending-admin-service`). The service uses the same database, in its own schema `lcm`.

```yaml
  - type: web
    name: lending-lcm-service
    runtime: docker
    plan: free
    region: singapore
    dockerfilePath: ./lending-lcm-service/Dockerfile
    dockerContext: ./lending-lcm-service
    healthCheckPath: /actuator/health
    envVars:
      - key: PORT
        value: "10000"
      - key: DB_HOST
        fromDatabase: { name: lending-db, property: host }
      - key: DB_PORT
        fromDatabase: { name: lending-db, property: port }
      - key: DB_NAME
        fromDatabase: { name: lending-db, property: database }
      - key: DB_USER
        fromDatabase: { name: lending-db, property: user }
      - key: DB_PASSWORD
        fromDatabase: { name: lending-db, property: password }
      - key: LCM_DB_SCHEMA
        value: lcm
      - key: LCM_SERVICE_USERNAME
        value: lcm-service
      - key: LCM_SERVICE_PASSWORD
        generateValue: true   # read it in the Render dashboard, then copy it to LENDING_LCM_PASSWORD on lending-service
      - key: LCM_ENFORCE
        value: "true"
      - key: JAVA_TOOL_OPTIONS
        value: "-Xmx256m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
```

Add to the `lending-service` `envVars` (leave the mode `off` until LCM is deployed and loaded):

```yaml
      - key: LENDING_LCM_MODE
        value: "off"            # off | shadow | live
      - key: LENDING_LCM_URL
        value: https://lending-lcm-service-XXXX.onrender.com   # copy from the dashboard
      - key: LENDING_LCM_USERNAME
        value: lcm-service
      - key: LENDING_LCM_PASSWORD
        sync: false             # paste the LCM service password
```

Free-tier note: a sleeping LCM answers the first call slowly. In `shadow` that is harmless; in `live` raise
`LENDING_LCM_READ_TIMEOUT_MS` or keep LCM awake, otherwise the first disbursement after a quiet spell fails closed.

## 3. `CHANGELOG.md`: under `## [Unreleased]` / `### Added`

```markdown
- **Limits and Collateral Management (LCM): a new, separate service, and the hooks in lending-service.** Built from *LCM Requirements v0.1* (`docs/LCM_Requirements_v0.1.docx`, 102 requirements, 24 scenarios). Phases A to C are built; D to F (review workflow, group exposure, FX / vendor integrations / reporting) are not.
  - **`lending-lcm-service`** (port 8084, Java 17, schema `lcm`, Flyway history `lcm_schema_history`, three migrations, 14 tables, 49 endpoints). Never reads or writes lending tables. Facilities in a hierarchy (OVERALL / SUBLIMIT / PRODUCT, four levels, revolving or term) with maker-checker changes and temporary excess; availability = effective limit - consumed - reserved at every level; reserve / confirm / release / repay / close with expiring reservations, `Idempotency-Key`, ordered row locking and an append-only `limit_txn` ledger (`GET /monitoring/integrity` proves counters equal the ledger). Collateral register with maker-checker valuations, legal / charge / insurance rules and activation; allocation by percentage; drawing power (value x (1 - haircut) - prior charge, x max LTV, x allocation %) caps a drawing-power-governed facility. Monitoring sweep (reservation and facility expiry; OVER_LIMIT, DRAWING_POWER_SHORTFALL, EXPIRED_WITH_UTILISATION, VALUATION_STALE, INSURANCE_EXPIRED breaches), pull event feed that never skips an event, customer portal read models, and a bridge that loads existing sanctions, open positions and mortgages. **The seeded haircuts, LTVs and validity periods are placeholders for Credit Policy.** Security is one Basic service credential (the swap point for the IAM resource server); each endpoint names its future permission code (`IAM_PERMISSIONS.txt`, 29 codes).
  - **lending-service, migration V19.** `loan_account.limit_facility_ref` and `limit_reservation_ref` (nullable) and the `limit_outbox` retry table. New optional `limitFacilityRef` on create-account and on the account response. Package `com.bank.lending.limits` (`LimitGateway`, `LimitClient` on `java.net.http`, outbox, `/api/v1/limits` operations endpoints).
  - **`lending.lcm.mode` = `off` (default) | `shadow` | `live`.** Off changes nothing. Shadow records usage and what LCM would have refused and never blocks. Live enforces: a disbursement over the limit returns 422 with the LCM code and changes nothing; LCM unreachable returns 503 unless `lending.lcm.fail-open=true`. Only accounts with a facility reference are limit-controlled.
  - **How it hooks in.** Disbursement reserves first (so a refusal leaves the account untouched) and queues the confirmation in the loan transaction; principal repayments (EMI, part-payment, foreclosure) and closure are queued the same way, keyed by transaction id. Delivery is after commit on a background thread plus a one-minute sweep, in order per account, leased across nodes, with SENT / SKIPPED / DEAD outcomes; a rolled-back disbursement releases its reservation. A payment never waits for LCM. No write-off hook (lending has no write-off path).
  - **Maintenance clear-data now also empties `limit_outbox`** (it references `loan_account`); `MaintenanceServiceTest` counts 21 tables.
  - **Verification.** Unit tests of the pure rules and of the lending-side client, gateway and queue; PostgreSQL 16 runs of every LCM migration and of the whole scenario (175 checks, including concurrent drawdowns) and of V19 and the queue SQL (47 checks, including two-node claiming). **Not run:** the Maven build, Spring wiring, Testcontainers tests, and compilation of the edited lending classes (no Maven Central here).
  - **Deploy order:** lending-lcm-service first (it creates its schema); run the bridge (`scripts/bridge_from_lending.sql`, three imports); then lending-service (V19) and `scripts/link_lending_accounts.sql`; set `LENDING_LCM_MODE=shadow`, compare, then `live`.
```

## 4. `README.md`

Services table, after the admin row:

```markdown
| `lending-lcm-service` | Limits and collateral management: facilities, reservations, collateral, drawing power, breaches. Own schema `lcm` in the shared database; lending-service calls it when `lending.lcm.mode` is `shadow` or `live` | 8084 / 10000 |
```

Docs table:

```markdown
| `docs/LCM_Requirements_v0.1.docx` | Limits and Collateral Management requirements: 102 requirements with phase and acceptance criterion, 24 scenarios, lending differences, open questions with recommended defaults |
```

Run section, one line: `#   LCM service      http://localhost:8084   (lcm-service / changeme; Swagger at /swagger-ui.html)`

Known-limits paragraph, append: "LCM and its lending hooks have not had a Maven build; `lending.lcm.mode` is `off` by default."

## 5. Environment variables

| Service | Variable | Default | Meaning |
|---|---|---|---|
| lending-service | `LENDING_LCM_MODE` | `off` | `off`, `shadow`, `live` |
| lending-service | `LENDING_LCM_URL` | `http://localhost:8084` | LCM base URL |
| lending-service | `LENDING_LCM_USERNAME` / `LENDING_LCM_PASSWORD` | `lcm-service` / `changeme` | service credential (change it) |
| lending-service | `LENDING_LCM_CONNECT_TIMEOUT_MS` / `LENDING_LCM_READ_TIMEOUT_MS` | 2000 / 5000 | per attempt |
| lending-service | `LENDING_LCM_RETRIES` | 2 | extra attempts on I/O error, 5xx, 429 |
| lending-service | `LENDING_LCM_FAIL_OPEN` | `false` | live only: disburse anyway when LCM is unreachable |
| lending-service | `LENDING_LCM_RESERVATION_TTL_MINUTES` | 30 | reservation life |
| lending-service | `lending.lcm.outbox.*` (application.yml) | 50 / 10 / 5 / cron every minute | batch size, max attempts, lease minutes, retry cron |
| lending-lcm-service | `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD`, `LCM_DB_SCHEMA` | see README | shared Postgres, own schema |
| lending-lcm-service | `PORT` | 8084 | |
| lending-lcm-service | `LCM_SERVICE_USERNAME` / `LCM_SERVICE_PASSWORD` | `lcm-service` / `changeme` | Basic credential (change it) |
| lending-lcm-service | `LCM_ENFORCE` | `true` | `false` makes every drawdown shadow |
| lending-lcm-service | `LCM_MAKER_CHECKER_ENFORCE` | `true` | maker and checker must differ |
| lending-lcm-service | `LCM_RESERVATION_TTL_MINUTES` / `LCM_RESERVATION_MAX_TTL_MINUTES` | 30 / 1440 | |
| lending-lcm-service | `LCM_SWEEP_CRON` | `0 * * * * *` | `-` disables the timer |

## 6. Things to know before you apply

* **Migration number.** V19 is the next lending migration today. If the IAM work or anything else lands a V19 first, rename `V19__limit_integration.sql` and the comment references to it (`link_lending_accounts.sql` header, README, CHANGELOG).
* **Existing tests edited:** `AccountLevelBatchOperationsTest` (one more constructor argument), `MaintenanceServiceTest` (21 tables, 42 rows), `FlywayMigrationTest` (`limit_outbox` in the table list).
* **API shape change:** `CreateLoanAccountRequest` has an 18th component (`limitFacilityRef`); the old 17-argument constructor is kept, so existing callers compile. `LoanAccountResponse` gains two fields at the end of the business fields, before `version`; any client that builds the record positionally must be updated (none in the repository does).
* **Shared Postgres:** LCM's Flyway runs in schema `lcm` and its history table is `lcm_schema_history`, so it cannot collide with lending's `flyway_schema_history`. The database role needs CREATE on the database for the first start (Flyway creates the schema).
