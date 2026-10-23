# lending-chit-service: build status

Chit fund microservice for the lending platform. Built in phases from `docs/Chit_Fund_Specification_Prompt_v2.md`.
Last updated 23 Oct 2026. **Phases 1 and 2 of 10 are done. Phases 3 to 9 are not started.**

## Phase 0: discovery note

### Assumptions

1. Separate Maven project like `lending-lcm-service`: Java 17, Spring Boot 3.3.2, schema `chit`, history table `chit_schema_history`, port 8086, API root `/api/v1/chit`, variables prefixed `CHIT_`.
2. Own double-entry journal (no reuse of the lending accounting engine). Events by table plus pull feed; Kafka only as an optional extension.
3. Operator screens go into the existing React admin console; subscriber screens wait for the customer portal.
4. Cycle dates follow the platform business date from lending-service. A customer is a `customerId` number.
5. Rounding is HALF_UP at 2 decimals. Percentages are written as 5 for 5%. Late fees count calendar days.
6. Rules can be edited only while a group is DRAFT or OPEN_FOR_ENROLMENT; they are frozen from FULL onward.
7. A standard chit has `cycleCount = ticketCount`, so every ticket wins exactly once. The validator enforces it.
8. Group `FULL` may go back to `OPEN_FOR_ENROLMENT` when a ticket is withdrawn before activation.
9. Ticket `TRANSFERRED` is a transient state while a transfer awaits approval; the ticket then returns to ACTIVE or PRIZED with a new subscriber link. A cured defaulter returns to PRIZED if it had won, otherwise ACTIVE.
10. `ROLL_OVER_CYCLE` sends an AUCTION_OPEN cycle back to COLLECTION_OPEN with the calendar extended.
11. No-bid handling: LOTTERY draws a winner at the minimum discount; FOREMAN_DEFAULT_DISCOUNT draws a winner at the foreman's default discount (needs `foremanDefaultDiscountPct`).

### Open questions for you

1. Is `TRANSFERRED` as a transient state right, or do you want the old ticket record closed and a new one opened?
2. With `NON_PRIZED_ONLY`, does the winner of the current cycle share that cycle's dividend? (The calculator takes the list of beneficiaries from the caller, so either works; the service must choose.)
3. Exit of a non-prized ticket: refund paid contributions less penalty, or require a replacement subscriber? The journal for it waits for this answer (Phase 6).
4. When the pool bears the loss of a write-off, who absorbs it (reduce later dividends, or the foreman)? Needed for the Phase 6 journals.
5. Late fees on calendar days is assumed. Business days instead?
6. Is a strict `cycleCount = ticketCount` acceptable, or do you want shorter or longer terms?

### Mismatches between the prompt and the repo

- The prompt's source description said Angular; the admin console is React 18 / Vite / TypeScript. Prompt v2 already follows the repo.
- Port 8083 looks unused; 8086 is used here. Check on your side.
- The root `pom.xml` listed five modules; this one is added in Phase 2 (after `lending-lcm-service`).
- **JDBC, not JPA.** The prompt says JPA entities. `lending-lcm-service` uses `JdbcTemplate` and plain SQL, so this service does too: the same pattern, no second persistence style to maintain, and the SQL is visible and testable against Postgres directly. This is a deliberate deviation from the prompt; say so if you want JPA instead.
- **Events.** The platform does have a broker in `docker-compose.yml` (Kafka) and an optional Kafka flag in the batch service, but `render.yaml` has none and the batch service runs with it off. So events are rows in `chit_event` read through a pull feed, as in LCM. A Kafka publisher can be added later without changing the feed.
- `docker-compose.yml` does not contain `lending-lcm-service` or `lending-iam-service` today. Phase 2 adds only the chit service to it, as the prompt asks; the LCM and IAM gaps are left alone.

### Proposed package structure

```
com.bank.lending.chit
  common/        Money, ChitException, ConfigInvalidException, RuleViolation          (Phase 1, built)
  domain/        enums and statuses                                                   (Phase 1, built)
  domain/rules/  ChitRules, LateFeeRule, DefaultThreshold, RulesValidator, RulesEditPolicy   (Phase 1, built)
  domain/state/  StateMachine, Lifecycles                                             (Phase 1, built)
  calc/          LargestRemainder, AuctionSettlementCalculator, AllocationEngine,
                 LateFeeCalculator, Draw                                              (Phase 1, built)
  config/        SecurityConfig, ApiExceptionHandler, ChitSettings, ClockConfig       (Phase 2)
  group/ ticket/ cycle/ demand/ payment/ auction/ payout/ dividend/ security/ default/ transfer/ closure/   (Phases 3 to 7)
  ledger/        journal, accounts, templates, trial balance, reconciliation          (Phase 2 onward)
  events/        EventFeed and the event writer                                       (Phase 2)
  maintenance/   status and clear-data                                                (Phase 2)
  portal/        customer-scoped read models                                          (Phase 7)
  platform/      LendingClient (business date, working days, holidays)                (Phase 3)
```

### Journal templates still to review (the core ones are in section 12 of the prompt)

New accounts these need: `FOREMAN_FLOAT_PAYABLE`, `TRANSFER_FEE_INCOME`, `WRITE_OFF_LOSS`, `GUARANTEE_LOSS_EXPENSE`.

| Event | Debit | Credit |
|---|---|---|
| Security applied to a prized defaulter's arrears | SECURITY_DEPOSIT_LIABILITY[t]; then POOL_CASH | SUBSCRIBER_RECEIVABLE[t]; then SECURITY_CASH |
| Foreman covers a shortfall (pays the pool) | POOL_CASH; then FOREMAN_GUARANTEE_RECEIVABLE[t] | FOREMAN_CASH; then SUBSCRIBER_RECEIVABLE[t] |
| Ticket repays a guaranteed amount | FOREMAN_CASH | FOREMAN_GUARANTEE_RECEIVABLE[t] |
| Guarantee written off, foreman bears the loss | GUARANTEE_LOSS_EXPENSE | FOREMAN_GUARANTEE_RECEIVABLE[t] |
| Receivable written off, pool bears the loss (needs open question 4) | WRITE_OFF_LOSS | SUBSCRIBER_RECEIVABLE[t] |
| Foreman floats a liquidity gap | POOL_CASH | FOREMAN_FLOAT_PAYABLE |
| Float repaid from later collections | FOREMAN_FLOAT_PAYABLE | POOL_CASH |
| Ticket transfer fee | FOREMAN_CASH | TRANSFER_FEE_INCOME |
| Cancellation refund of fees | ENROLMENT_FEE_INCOME | FOREMAN_CASH |
| Cancellation refund of a deposit | SECURITY_DEPOSIT_LIABILITY[t] | SECURITY_CASH |
| Exit refund | waits for open question 3 | |

## Phase 1: what is built

Pure Java, no Spring, no database. 33 main classes and 8 test classes with 81 tests, all under `lending-chit-service/`.

| Area | Classes | What it does |
|---|---|---|
| Money | `common/Money` | Scale, percentage, paise conversion; one rounding mode |
| Errors | `common/ChitException`, `ConfigInvalidException`, `RuleViolation` | Stable error codes; no HTTP status in this layer |
| Statuses and rules enums | `domain/*` (16 enums) | Group, ticket and cycle statuses; every configurable choice from the prompt |
| Rules | `domain/rules/ChitRules` (+ builder), `LateFeeRule`, `DefaultThreshold` | The whole configuration as data |
| Validation | `RulesValidator`, `RulesEditPolicy` | 18 error codes, all reported together, including `DISCOUNT_BELOW_COMMISSION` |
| State machines | `domain/state/StateMachine`, `Lifecycles` | Explicit transition tables for group, ticket and cycle; illegal moves refused with `ILLEGAL_TRANSITION` |
| Dividend split | `calc/LargestRemainder` | Exact, deterministic split in paise (ties go to the lower ticket number) |
| Auction money | `calc/AuctionSettlementCalculator`, `AuctionSettlement` | Prize, commission, dividend pool and shares; last cycle with both commission rules; negative pool refused |
| Payments | `calc/AllocationEngine` | Late fees, then oldest arrears, then the current instalment, then advance; custom order; partial payments |
| Late fees | `calc/LateFeeCalculator` | Flat or percent per day, grace, caps, safe to rerun and to catch up |
| Draw | `calc/Draw` | Reproducible winner or full order from a seed (SHA-256 based), with an audit record |

Fixtures from section 17 of the prompt are tests: A (even dividend), B (15,000.07 over 20 tickets: tickets 1 to 7 get 750.01), D (last cycle, both commission rules) and E (minimum discount below the commission is rejected). Fixture C (liquidity gap) belongs to Phase 5.

Not yet built, by design: winner selection and bid validation, the liquidity check, the calendar generator, and everything with a database or HTTP.

## Phase 1: how it was checked, and what was not

- **Done:** every main and test class compiles with `javac` (Java 21 here, source is Java 17 compatible, no preview features). The 81 tests were run with a small stand-in for the JUnit launcher, because Maven Central cannot be reached from the build environment. All passed. A deliberate break of the tie-break rule made 3 tests fail, so the tests do catch that.
- **Not done:** a Maven build, the real JUnit 5 run, the Spring Boot parent and dependency resolution, Testcontainers (not used yet), and any code review by you.
- The tests use only `@Test` and the common `Assertions` methods, so they should run unchanged under JUnit 5. If one does not compile, tell me.



## Phase 2: what is built

The service is now a Spring Boot application: it starts, migrates its own schema, authenticates callers and answers health. No business feature yet.

| Area | What | Notes |
|---|---|---|
| Application | `ChitApplication`, `application.yml`, `Dockerfile`, `pom.xml` | Port 8086, schema `chit`, history table `chit_schema_history`, env prefix `CHIT_`. Same as LCM apart from names. |
| Security | `config/SecurityConfig` | One Basic credential for `/api/**`, JSON 401. Identical to the LCM class apart from names; the IAM swap point. |
| Errors | `config/ApiExceptionHandler`, `config/ErrorStatus` | `{timestamp,status,code,message}`; rule violations list every violation. One table maps a code to a status (400, 403, 404, 409, 422; 500 for internal-consistency codes). |
| Schema | `V1__chit_core.sql`, `V2__chit_ledger_support.sql` | 24 tables. V2 adds idempotency, event feed, audit and the ledger. |
| Ledger model | `ledger/AccountType`, `AccountRef`, `Line`, `JournalDraft`, `LedgerBalances` | A journal cannot be built unbalanced; zero lines are dropped, negative ones refused. |
| Journal templates | `ledger/JournalTemplates` | 20 templates, pure functions. The 6 core ones follow section 12 of the prompt; the rest are the reviewed list above (marked REVIEW in the code). |
| Ledger service | `LedgerService`, `LedgerRepository`, `LedgerController` | `post` (idempotent by posting key, joins the caller's transaction), `reverse` (mirror journal, once), trial balance, journal reads. |
| Idempotency | `idempotency/IdempotencyStore`, `IdempotencyCheck` | Claim a key; replay the earlier result; a different request on the same key is a 409. |
| Events | `events/EventFeed`, `common/Audit` | Pull feed with the gap-free `txid-id` cursor; audit log. |
| Maintenance | `maintenance/*` | Status and clear-data behind `CHIT_RESET_ENABLED` and the word `RESET`; one `TRUNCATE ... RESTART IDENTITY` over 24 tables. |
| Wiring | root `pom.xml`, `render.yaml`, `docker-compose.yml` | New module and service blocks. See the repo diff. |

### Database rules (checked on PostgreSQL 16)

Journals need two or more lines and debits equal to credits, checked at commit by a deferred constraint trigger. Journals and lines are append-only. A line must sit on an account of its own group. A posting key is used once. Per-ticket accounts must name a ticket, group accounts must not. Also from V1: one winner per cycle, one prize per ticket, one open subscriber link per ticket, a demand cannot be overpaid, maker is not checker (payout, transfer, write-off), a frozen rules version cannot change, `cycleCount = ticketCount` and `subscription x tickets = chit value`.

### Ledger accounts and the two decisions still open

New accounts beyond the prompt: `FOREMAN_FLOAT_PAYABLE`, `TRANSFER_FEE_INCOME`, `WRITE_OFF_LOSS`, `GUARANTEE_LOSS_EXPENSE`. Journal for the **prize hold** is new in this phase: when part of a prize is held back as security, `Dr PRIZE_PAYABLE gross, Dr SECURITY_CASH hold / Cr POOL_CASH gross, Cr SECURITY_DEPOSIT_LIABILITY[t] hold`. Please review it with the other REVIEW templates. The exit-refund journal is still not written; it waits for open question 3. Who finally absorbs a write-off (open question 4) does not change the journal, only who is charged in Phase 6.

## Phase 2: how it was checked, and what was not

- **Database: checked.** V1 and V2 apply cleanly on PostgreSQL 16 in order. `scripts/verify_schema.sql` exercises 26 rules (each refused case is attempted and must fail) and passes. Every SQL statement the ledger repository, idempotency store and event feed use was run in psql with real values. The reset's single `TRUNCATE ... RESTART IDENTITY` over all 24 tables was run with data in every table family and restarts the ids at 1.
- **Pure code: checked.** 133 tests pass under the stand-in runner (81 from Phase 1, 52 new): journal rules, all 20 templates, the error-status table, actor/hash/JSON helpers, the idempotency decision, the event cursor, the maintenance refusals, and three drift guards (Java enums against the SQL CHECK lists, the reset list against the tables the migrations create). `TwentyCycleSimulationTest` runs a full 20-ticket chit through the templates under both last-cycle commission rules and an uneven dividend; the pool and every payable net to zero after the closure sweep and the pool never goes negative. A deliberate break of the auction journal made 4 of those tests fail.
- **Spring code: compiled against stand-ins only.** Maven Central cannot be reached from the build environment, so the Spring classes were compiled against small hand-written stubs of the Spring, Swagger types they use (JdbcTemplate, annotations, ResponseEntity and so on), with the real Jackson and SLF4J jars. That proves the code is type-correct against the signatures I stubbed, not that it runs. `SecurityConfig` could not be stubbed; it is a straight copy of the LCM class (diff checked, names only).
- **Not done:** `mvn` build, the real JUnit 5 run, Spring context startup, Flyway running the migrations through Spring, the Testcontainers test `ChitApplicationIT` (written, never executed), the Docker build, the `render.yaml` and `docker-compose.yml` changes on real infrastructure, and any review by you.

If `mvn test` fails on first run, send me the output; the likeliest causes are a mismatch between my stubs and the real Spring signatures, or the IT needing Docker.

## Next: Phase 3

Group setup and enrolment: create a group with its rules (validated), open enrolment, enrol tickets and subscribers, the cycle calendar generator, the platform business-date client, activation with rules frozen, and the admin screens' first API. It needs your answers to open questions 1 and 6 before it starts, because they change the ticket and group model. Questions 3 and 4 are still only needed for Phase 6.
