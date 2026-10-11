# Discovery report - what the platform has today, and what the lifecycle suite can therefore prove

Scope: read-only inspection of the repository at commit `ce38e66` (lending-service, lending-batch-service,
lending-admin-service, `render.yaml`). **No production code was changed.** Items marked **NOT FOUND** were searched for
in code, migrations and published API surface; each carries the smallest change that would provide it. None of those
changes will be made without your explicit approval (see "Proposed changes - awaiting approval").

## 1. Inventory by lifecycle step

| Step | How it is done today | Where | Suite approach |
|---|---|---|---|
| Create account | `POST /api/v1/loan-accounts` (also `/bulk/create`), product-driven terms, validation returns all violations in one 400 | lending-service | API |
| Disburse + schedule | `POST /api/v1/loan-accounts/{id}/disburse` (`disbursedAmount`, `firstEmiDueDate`); generates the schedule; no disbursement-date parameter | lending-service | API |
| Read schedule | `GET /loan-accounts/{id}/schedule` (current), admin `GET /api/admin/accounts/{id}/schedule` (all versions, status) | both | read-only |
| Daily accrual | `POST /api/v1/interest-accruals/run?businessDate=` (lending-service) **and** batch job `INTEREST_ACCRUAL_DAILY`. **The batch job body is a stub** | both | batch trigger (decision); API only if `e2e.accrual.path=API` |
| Business date | no service; the date is a **job parameter** (`businessDate`) and a request parameter (`asOfDate`, `valueDate`, `businessDate`) | batch / lending | the suite owns the simulated calendar and passes the date everywhere |
| Delinquency (DPD / SMA / NPA) | `POST /loan-accounts/delinquency-classifications {asOfDate}`; hard-coded buckets (<=0 ACTIVE, <=30 SMA0, <=60 SMA1, <=90 SMA2, <=365 SUBSTANDARD, <=1095 DOUBTFUL, else LOSS); marks the oldest unpaid due instalment OVERDUE; reverts to ACTIVE when nothing is unpaid-and-due. Batch jobs `DPD_UPDATE_DAILY`, `NPA_CLASSIFICATION_CHECK_DAILY` are **stubs** | both | batch trigger; API only if `e2e.dpd.path=API` |
| Payment | `POST .../payments/emi` (needs `Idempotency-Key`, amount **must equal the exact instalment due**), `/part-payment` (principal prepayment, reschedules), `/foreclosure`; channels NACH / MANUAL / ONLINE | lending-service | API |
| Payment by file / batch | `AUTO_DEBIT_PROCESSING_DAILY` is a **stub** | batch | not available - see open question 3 |
| Batch operation | `/api/v1/batch-admin/jobs`, `/jobs/{job}/trigger` (body `businessDate`, `jobParams`), `/jobs/{job}/executions`, `/executions/{id}/errors`, `/status/today`; HTTP Basic, role BATCH_ADMIN. 36 jobs (13 daily, 11 monthly, 7 periodic, 5 event). **Every job body is "TODO: business logic"** | batch | trigger, then poll the execution until terminal |
| Restart / idempotency contract | restartable jobs are identified by `businessDate` only (a COMPLETED instance cannot be re-run); non-restartable jobs get a fresh `runId` each launch; a second RUNNING launch is refused | batch | tested (BAT-04), not assumed |
| Verification reads | admin portal: accounts, schedule, accruals, payments, ledger outbox, batch jobs/executions over HTTP Basic | admin | decision: admin read APIs |
| General ledger | only `ledger_outbox` with a logging stub gateway (`LoggingLedgerGatewayClient`) | lending-service | outbox presence only (LED-01) |

## 2. NOT FOUND, with the smallest proposed change

| # | Capability | Evidence | Smallest proposed change | Needs from you |
|---|---|---|---|---|
| P1 | Batch jobs that actually do the work | all 36 job bodies are stubs | implement bodies of `INTEREST_ACCRUAL_DAILY`, `DPD_UPDATE_DAILY`, `NPA_CLASSIFICATION_CHECK_DAILY`, `SOD_EOD_BALANCING_DAILY` by delegating to the existing lending-service accrual / classification services (no new rules) | approve |
| P2 | Arrears / demand records | no table, entity or job; "overdue" is only `amortization_schedule.installment_status` | an `arrears` table written by the DPD job (one row per missed instalment: due date, amount, cleared date) | approve + policy |
| P3 | Persisted DPD | DPD is computed on read (admin uses the wall-clock date) | `days_past_due` column on `loan_account`, written by the DPD job for the business date | approve |
| P4 | Configurable DPD / NPA thresholds | bucket limits are literals in `classifyByDaysPastDue` | move to `batch_job_config`/product-level settings | approve + values |
| P5 | Ledger postings | outbox only; gateway logs | outbox-based reconciliation first; real GL later | policy |
| P6 | Provisioning, NPA interest suspension / reversal, penal interest | no code, table or endpoint | needs your accounting policy (rates, rules) before design | policy |
| - | Business-date / EOD rollover | none | optional: a `business_date` table the jobs read | decision |
| - | Tranche disbursement, restructure after delinquency, write-off, cure-period upgrade rule, partial-instalment allocation | none | each is a feature, not a patch | decision |
| - | Partitions | `interest_accrual` and `emi_transaction` partitions exist only for Oct 2026 - Dec 2027 (V9) plus rolling maintenance | extend for the simulated window, or keep `e2e.start-date` inside the covered range | action |

## 3. Consequence for the suite (Phase 1)

With "batch service only" and the jobs still stubs, a run against the current build will **complete every job
successfully while processing zero records**. The suite reports exactly that (BAT-03, ACC-01, DPD-01, NPA-01, ARR-01 and
the downstream checks) as defects, ranked by severity, rather than papering over it. Everything that does not depend on
those jobs (account creation, schedules for all five schedule types and five day counts, payments, idempotency,
part-payment, foreclosure, closure, balances, batch trigger / polling / re-run behaviour) is asserted for real.

Two ways to see the full lifecycle pass today, both explicit and labelled in the report: approve P1, or set
`e2e.accrual.path=API` / `e2e.dpd.path=API` to call the lending-service endpoints directly (a deviation from the agreed
batch-only path, recorded under "Deviations" in every report).

## 4. Proposed changes - awaiting approval

None applied. Reply with the P-numbers you approve; P1 is the only one that turns most stub-driven failures green without
inventing rules.
