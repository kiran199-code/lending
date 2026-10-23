# lending-chit-service: build status

Chit fund microservice for the lending platform. Built in phases from `docs/Chit_Fund_Specification_Prompt_v2.md`.
Last updated 23 Oct 2026. **Phases 1, 2 and 3 of 10 are built. Phases 4 to 9 are not started.** Phase 3 is awaiting your review; nothing from it is committed.

## Phase 0: discovery note

### Assumptions

1. Separate Maven project like `lending-lcm-service`: Java 17, Spring Boot 3.3.2, schema `chit`, history table `chit_schema_history`, port 8086, API root `/api/v1/chit`, variables prefixed `CHIT_`.
2. Own double-entry journal (no reuse of the lending accounting engine). Events by table plus pull feed; Kafka only as an optional extension.
3. Operator screens go into the existing React admin console; subscriber screens wait for the customer portal.
4. Cycle dates follow the platform business date from lending-service. A customer is a `customerId` number.
5. Rounding is HALF_UP at 2 decimals. Percentages are written as 5 for 5%. Late fees count calendar days.
6. Rules can be edited only while a group is DRAFT or OPEN_FOR_ENROLMENT; they are frozen from FULL onward.
7. A standard chit has `cycleCount = ticketCount`, so every ticket wins exactly once. The validator enforces it.
8. Group `FULL` may go back to `OPEN_FOR_ENROLMENT` when a ticket is withdrawn before activation. (Built in Phase 3.)
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
  platform/      LendingPlatformClient (business date, working-day calendar)         (Phase 3, built)
  calendar/      CycleCalendarGenerator                                               (Phase 3, built)
  enrolment/     quote, enrol, withdraw, verify, activate, cancel                     (Phase 3, built)
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

## Decisions you gave, as applied

- **Ticket transfer (your Option A).** A transferred ticket stays the same record. The holder changes through the subscriber link and history and arrears travel with it. In Phase 3 this shows up as: `chit_ticket.customer_id` is the *current holder* and every enrolment opens a subscriber link; the transfer in Phase 6 must update both in one transaction.
- **Strict term (your "yes strict").** Cycles must equal tickets, so every ticket wins exactly once. The validator and a database CHECK already enforce it; the calendar generator builds exactly one cycle per ticket.

## Phase 3: what is built

Enrolment, activation, the calendar, security and fees. 25 new main classes and edits to 7 existing ones, one new migration (V3), 43 tests added (176 in all).

| Area | What | Notes |
|---|---|---|
| Schema | `V3__chit_enrolment.sql` | Seat numbers can be reused after a withdrawal (partial unique index on live tickets); `chit_fee_receipt`; security rows carry received date, verifier, reference and return date; cancel and activation details on the group. 25 tables now. |
| Rules | `securityDepositPct` added to `ChitRules` and validated; `RulesDefaults` fills optional rules | Only the required fields have to be sent. `subscription` defaults to value / tickets when that divides exactly. |
| Calendar | `calendar/CycleCalendarGenerator` | Monthly (day of month; a day past month end means the last day) and weekly or fortnightly (weekday). A non-working day moves *forward* (at most 14 days). Refused with `CALENDAR_OVERLAP` if an auction would not be before the next due date. |
| lending-service client | `platform/LendingPlatformClient` | Reads `GET /api/v1/business-date` and `/working-day?date=`. Read timeout 90 s (a sleeping free-plan lending-service), one retry on 502/503/504 or an I/O error, then `503 BUSINESS_DATE_UNAVAILABLE`; nothing is changed. Optional Basic credential. |
| Groups | `group/*` | Create (DRAFT), list, get, rules versions (new version on each change), open enrolment, cycle list, preview. |
| Enrolment | `enrolment/*` | Quote, enrol (several tickets per customer), withdraw, verify security, cancel, activation check, activate. |
| Ledger | `LedgerService.ensureGroupAccounts`; fee and deposit journals | Posting keys: `ENROL_FEE:<ticketId>:ENROLMENT`, `ENROL_FEE:<ticketId>:REGISTRATION`, `SECURITY_RECV:<ticketId>`, refunds `ENROL_FEE_REFUND:...`, `SEC_REFUND:<ticketId>`. |
| Events | `ChitGroupCreated`, `ChitRulesUpdated`, `EnrolmentOpened`, `TicketEnrolled`, `ChitGroupFull`, `TicketWithdrawn`, `SecurityVerified`, `ChitGroupActivated`, `ChitGroupCancelled` | In `chit_event`, readable through the feed. |
| Errors | `BUSINESS_DATE_UNAVAILABLE` 503; 409 for `GROUP_CODE_EXISTS`, `GROUP_FULL`, `ENROLMENT_NOT_OPEN`, `WITHDRAWAL_NOT_ALLOWED`, `RULES_LOCKED_BY_ENROLMENT`, `ACTIVATION_BLOCKED`; 403 `MAKER_CHECKER_VIOLATION` | Others (amount mismatch, guarantor, calendar) are 422. |
| Permissions | 7 new codes in `IAM_PERMISSIONS.txt` | `chit.group.*` and `chit.enrolment.*`. |

### How activation works

1. A repeated request (same key) is answered first, without touching lending-service.
2. The group, rules and tickets are read and every blocker checked (`GROUP_NOT_FULL`, `TICKETS_INCOMPLETE`, `SECURITY_MISSING`, `SECURITY_NOT_VERIFIED`). Only then is the whole calendar built from lending-service, so no lock or transaction is held during those calls.
3. One transaction takes the group's row lock, refuses if the group changed since step 2 (`DATA_CONFLICT`, nothing written, repeat the call), re-checks everything including `FIRST_DUE_DATE_PASSED` against the business date, then freezes the rules version, sets tickets ACTIVE, inserts the cycles as SCHEDULED, creates the full chart of ledger accounts, sets the group ACTIVE and writes the event.

### New assumptions for you to confirm or change

1. **Security is collected at enrolment, from every ticket.** `DEPOSIT` = `securityDepositPct` % of the chit value, received in cash (Dr SECURITY_CASH / Cr SECURITY_DEPOSIT_LIABILITY). `LIEN_ON_DEPOSIT` = the same figure but **no cash moves**: the subscriber's own deposit is marked and its reference recorded. `GUARANTOR` = a different customer is named; no money. The legal limits on a deposit are not hard-coded (TODO-VERIFY). If you want security collected only when a ticket wins a prize, say so; that is a different flow (the prize-hold template already exists for it).
2. **Amounts received must equal the quote exactly.** No part payment or advance at enrolment; a short or excess amount is refused (`ENROLMENT_AMOUNT_MISMATCH`).
3. **Enrolment and registration fees both post to `ENROLMENT_FEE_INCOME`** with the same template, as two journals.
4. **Withdrawal before activation returns the deposit and keeps the fees** (setting `CHIT_WITHDRAWAL_REFUNDS_FEES`, default off). **Cancelling the group refunds everything**, ticket by ticket in number order: deposit, then enrolment fee, then registration fee. A lien is released, not paid.
5. **Once any ticket is enrolled these terms are locked:** chit value, ticket and cycle count, subscription, frequency, enrolment fee, registration fee, security rule and deposit percentage. Other rules can still change and create a new version. From FULL onward everything is frozen (as before).
6. **Seat numbers** are the lowest free number from 1 to N. A withdrawn ticket stays as an EXITED row and its number is reused by the next person.
7. **Maker-checker on security:** whoever recorded the security (the `X-Actor` of the enrolment call, or `lending-service` if none was sent) cannot verify it. Turn off with `CHIT_MAKER_CHECKER_ENFORCE=false` for single-person demos.
8. **Customers are not validated.** `customerId` is a number; the chit service does not check that the customer exists in lending-service. There is no customer lookup in the scope of this phase.
9. **The group code is supplied by the caller** (3 to 30 characters, capitals, digits, `-`, `_`), not generated.
10. **Activation needs the first instalment date on or after the business date.** The start date is not forced into the future when a draft is created.
11. **A repeated request returns current state, flagged `replayed: true`.** For withdraw and cancel the refund lines are rebuilt from the receipts and security rows, not stored, so they always match the books.
12. **Working-day lookups are one call per date** (about two per cycle). The weekend set is configurable in lending-service, so it is asked, not assumed.

## Phase 3: how it was checked, and what was not

- **Database: checked.** V1 to V3 apply cleanly on PostgreSQL 16; `scripts/verify_schema.sql` now runs 40 checks (refused and allowed cases) and every refused case is refused.
- **Pure code: checked.** 176 tests pass under the stand-in runner (133 before, 43 new): the calendar generator (month ends, leap year, weekly and fortnightly, holiday rolling, overlap), the lending-service client against a throw-away local HTTP server (success, retry, no retry on 4xx, outage, unreadable reply, credentials), quote and amount checks, activation blockers, refund plans, the money-term lock, and the rules snapshot round trip.
- **Services against a real database: checked, with limits.** The group, enrolment and activation services were run end to end against PostgreSQL 16 through a sandbox harness (150 checks, all passing): create and replay, wrong-key reuse, every refusal listed above, enrolling to FULL, withdrawal and seat reuse, rule locking, maker-checker, activation with a holiday and a weekend, replay, cancel with refunds (every ledger account back to zero and the trial balance in balance), guarantor and lien groups, event and audit rows. The harness is **not** part of the repo and is **not** Spring: it replaces `JdbcTemplate` with a bridge to PostgreSQL and wraps calls in transactions by hand. It does not exercise concurrent requests, the real `@Transactional` proxies, or JSON binding.
- **Controllers, wiring, JSON binding: compiled only.** The REST controllers and the Spring configuration compile against the stubs but have not run. In particular, binding the request body to the 34-field `ChitRules` record, the security filter on the new paths, and the 201 status are unverified until `mvn test` runs.
- **`ChitApplicationIT`: extended, never executed.** It now also drives a group from DRAFT to ACTIVE and a cancellation over HTTP, with a local stand-in for lending-service. It needs Docker.
- **The real lending-service was not called.** The client was tested against a stand-in built from the controller's source (`GET /api/v1/business-date` returns `businessDate`; `/working-day?date=` returns `workingDay`). On Render the first call may take a minute if lending-service is asleep.
- **Not done:** `mvn` build, real JUnit run, Spring startup, Flyway through Spring, Docker build, Render and Compose changes on real infrastructure, concurrent enrolment of the last seat, and your review.

## Next: Phase 4

Demands and payments: create each cycle's demands, collect and allocate payments (late fees, arrears, current), late-fee accrual, default detection and the batch jobs, all against the business date. Questions 3 and 4 are still only needed for Phase 6.
