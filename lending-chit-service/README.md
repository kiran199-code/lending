# lending-chit-service

Chit fund service for the lending platform: chit groups, tickets, cycles, subscriptions, auctions, prize payouts,
dividends, defaults and closure, with its own append-only double-entry journal. Same shape as `lending-lcm-service`:
Spring Boot 3.3.2, Java 17, JDBC (no JPA), Flyway, one service credential plus an `X-Actor` header.

Built in phases from `docs/Chit_Fund_Specification_Prompt_v2.md`. See `BUILD_STATUS.md` for what each phase delivers,
the assumptions, the open questions and what has and has not been verified.

**Where it stands: Phase 2 of 10.** The service starts, migrates its schema and answers health. The business features
(groups, tickets, cycles and so on) arrive in Phases 3 to 7; the endpoints below are all there is so far.

## Run it

```
mvn test                                  # unit tests; the *IT test needs Docker (Testcontainers)
mvn spring-boot:run                       # needs PostgreSQL at DB_HOST:DB_PORT/DB_NAME (default localhost:5432/lending)
```

Docker: `docker compose up --build lending-chit-service` from the repository root (it also starts PostgreSQL).
Render: the `lending-chit-service` block in `render.yaml`.

| Setting | Default | What it does |
|---|---|---|
| `PORT` | 8086 | HTTP port (Render sets 10000) |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD` | localhost 5432 lending lending lending | The shared PostgreSQL |
| `CHIT_DB_SCHEMA` | chit | Schema owned by this service; Flyway creates it. History table `chit_schema_history` |
| `CHIT_SERVICE_USERNAME` `CHIT_SERVICE_PASSWORD` | chit-service / changeme | The one HTTP Basic credential for `/api/**` |
| `CHIT_RESET_ENABLED` | false | Allows `POST /api/v1/chit/maintenance/clear-data` (non-production only) |
| `CHIT_MAKER_CHECKER_ENFORCE` | true | Reserved for Phase 5 onward |

## What exists in Phase 2

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Health, no credential |
| `GET /api/v1/chit/events?cursor=&limit=` | Pull feed of events, gap-free cursor, oldest first |
| `GET /api/v1/chit/ledger/groups/{id}/trial-balance` | Debit and credit totals per account; `inBalance` must be true |
| `GET /api/v1/chit/ledger/groups/{id}/journals?afterId=&limit=` | Journals with their lines |
| `GET /api/v1/chit/ledger/journals/{id}` | One journal |
| `GET /api/v1/chit/maintenance/status` | Whether clearing is on, and row counts |
| `POST /api/v1/chit/maintenance/clear-data` | Body `{"confirm":"RESET"}`; empties every chit table |
| `/swagger-ui.html` | API docs |

Errors are always `{timestamp, status, code, message}` (rule violations add `violations`). The code is the stable part;
`config/ErrorStatus` is the one place that maps a code to an HTTP status.

## The journal

Every money movement is a journal of at least two lines, debits equal to credits, built from `ledger/JournalTemplates`
(20 templates) and posted by `LedgerService.post` inside the caller's transaction. The database enforces the same
rules: a deferred trigger refuses an unbalanced journal at commit, journals and lines cannot be updated or deleted,
a line must sit on an account of its journal's group, and a posting key can be used once. A mistake is corrected by a
reversing journal (`LedgerService.reverse`), never by an edit. `TwentyCycleSimulationTest` runs a whole 20-ticket chit
through the templates and checks the pool nets to zero after the closure sweep.

`scripts/verify_schema.sql` exercises 26 of the database rules against a scratch database.

## Layout

```
common/        Money, ChitException, Actor, Audit, Json, Hashing
domain/        enums, rules, state machines                    (Phase 1)
calc/          settlement, allocation, late fees, draw          (Phase 1)
ledger/        journal model, templates, repository, service    (Phase 2)
idempotency/   key claim and replay check                       (Phase 2)
events/        pull feed                                        (Phase 2)
maintenance/   status and clear-data                            (Phase 2)
config/        security, error handling, settings               (Phase 2)
```

Replacing the Basic credential with the IAM JWT resource server changes `config/SecurityConfig` only; the actor then
comes from the token instead of `X-Actor`. Permission codes are in `IAM_PERMISSIONS.txt`.
