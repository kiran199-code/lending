# lending-chit-service

Chit fund service for the lending platform: chit groups, tickets, cycles, subscriptions, auctions, prize payouts,
dividends, defaults and closure, with its own append-only double-entry journal. Same shape as `lending-lcm-service`:
Spring Boot 3.3.2, Java 17, JDBC (no JPA), Flyway, one service credential plus an `X-Actor` header.

Built in phases from `docs/Chit_Fund_Specification_Prompt_v2.md`. See `BUILD_STATUS.md` for what each phase delivers,
the assumptions, the open questions and what has and has not been verified.

**Where it stands: Phase 3 of 10.** A group can be created, configured, opened for enrolment, filled with tickets
(fees and deposits posted to the ledger), activated (rules frozen, cycle calendar built on working days) or cancelled
with refunds. Demands, payments, auctions and payouts arrive in Phases 4 to 7.

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
| `CHIT_MAKER_CHECKER_ENFORCE` | true | Whoever records a ticket's security cannot verify it; also used from Phase 5 on |
| `LENDING_SERVICE_URL` | http://localhost:8080 | lending-service base URL: the business date and the working-day calendar are read from it |
| `CHIT_PLATFORM_USERNAME` `CHIT_PLATFORM_PASSWORD` | empty | Only if lending-service is protected |
| `CHIT_PLATFORM_READ_TIMEOUT_MS` | 90000 | Long, because a free-plan lending-service can take over a minute to wake |
| `CHIT_WITHDRAWAL_REFUNDS_FEES` | false | Whether a ticket withdrawing before the group starts gets its fees back (its deposit always comes back) |
| `CHIT_DB_POOL_SIZE` | 20 | Connection pool size; use 3 on the shared free database |

## What exists in Phase 3

All under `/api/v1/chit`. State-changing calls need an `Idempotency-Key` header; the same key and body returns the first
result with `"replayed": true`, the same key with a different body is `409 IDEMPOTENCY_KEY_REUSED`. Calls marked (actor)
also need `X-Actor`. A group is addressed by its `groupCode`.

| Endpoint | Purpose |
|---|---|
| `POST /groups` (actor) | Create a group in DRAFT. Body `{groupCode, name, foremanRef, rules}`; optional rules take defaults; 422 `CONFIG_INVALID` lists every violation |
| `GET /groups?status=&limit=&offset=` | Groups, newest first |
| `POST /groups/preview` | Validate rules and show the cycle dates, saving nothing |
| `GET /groups/{code}` | One group with seats taken and left |
| `GET /groups/{code}/rules`, `/rules/versions`, `/rules/versions/{n}` | Current rules and the history of versions |
| `PUT /groups/{code}/rules` (actor) | New rules version while DRAFT or OPEN; money terms lock once a ticket is enrolled |
| `POST /groups/{code}/open-enrolment` (actor) | DRAFT to OPEN_FOR_ENROLMENT |
| `GET /groups/{code}/enrolment-quote` | What each ticket must pay: fees, deposit or lien, guarantor needed |
| `POST /groups/{code}/tickets` | Enrol one ticket; amounts received must equal the quote; next free number assigned; becomes FULL at the last seat |
| `GET /groups/{code}/tickets?includeExited=` and `/tickets/{no}` | Tickets with fees and security |
| `POST /groups/{code}/tickets/{no}/withdraw` | Before activation: deposit returned, number freed, FULL goes back to OPEN |
| `POST /groups/{code}/tickets/{no}/security/verify` (actor) | Checker step; not the person who recorded it |
| `GET /groups/{code}/activation-check` | Every reason the group cannot start yet |
| `POST /groups/{code}/activate` (actor) | Freeze rules, build the calendar, create ledger accounts, set tickets ACTIVE |
| `POST /groups/{code}/cancel` (actor) | Before activation: refund deposits and fees, close the group |
| `GET /groups/{code}/cycles` | The cycle calendar (empty until activation) |

The business date and the working-day calendar come from lending-service, read before any database transaction starts. If
it cannot be reached the call answers `503 BUSINESS_DATE_UNAVAILABLE` and nothing is changed.

## What exists since Phase 2

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

`scripts/verify_schema.sql` runs 40 checks of the database rules (refused and allowed cases) against a scratch database.

## Layout

```
common/        Money, ChitException, Actor, Audit, Json, Hashing
domain/        enums, rules, state machines                    (Phase 1)
calc/          settlement, allocation, late fees, draw          (Phase 1)
calendar/      cycle dates on working days                      (Phase 3)
platform/      lending-service client: business date, calendar  (Phase 3)
group/         create, rules versions, open enrolment           (Phase 3)
enrolment/     quote, enrol, withdraw, verify, activate, cancel (Phase 3)
ledger/        journal model, templates, repository, service    (Phase 2)
idempotency/   key claim and replay check                       (Phase 2)
events/        pull feed                                        (Phase 2)
maintenance/   status and clear-data                            (Phase 2)
config/        security, error handling, settings               (Phase 2)
```

Replacing the Basic credential with the IAM JWT resource server changes `config/SecurityConfig` only; the actor then
comes from the token instead of `X-Actor`. Permission codes are in `IAM_PERMISSIONS.txt`.
