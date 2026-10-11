# lending-e2e-test-service

An end-to-end **lending lifecycle test suite** for the lending platform. It is a separate, independently built and
deployed project: its own `pom.xml`, executable jar, Docker image and release cycle. It talks to the platform **only over
HTTP** and is not a module of the root reactor, not a dependency of any production module, and is never packaged into a
lending artifact.

What one run does, in plain words:

1. Opens N test loan accounts (default **20**), half "good" borrowers (cohort A, must end CLOSED) and half "stress"
   borrowers (cohort B: missed payments, NPA paths, cures, foreclosure), spread over every product / schedule type /
   day-count in a data-driven scenario matrix.
2. Walks a **simulated calendar one business day at a time**. Each day the borrowers pay (through the lending API with the
   simulated day as value date), then the **real batch jobs are triggered for that business date** through the batch
   service's operator API and **polled until they reach a terminal state** (no fixed sleeps).
3. After every day, checks each live account **read-only** (admin portal read APIs) against the suite's **own independent
   ledger and calculators**: accrual, balance, status/DPD bucket, arrears marking, allocation, closure.
4. Writes a per-day, per-account **audit trail**, a **run report** and a **ranked defect list**.

It never re-implements batch logic, never calls a repository, and never writes a business table.

> Status: Phase 1. The platform's 36 batch job bodies are still stubs, so against today's build the accrual / DPD / NPA
> checks fail or report NOT FOUND - that is the finding, not a suite fault. See `docs/DISCOVERY_REPORT.md` for the
> inventory, the NOT FOUND list and the changes awaiting your approval.

## Quick start

```bash
# build (Java 17, Maven)  - from the repo root
mvn -f lending-e2e-test-service/pom.xml clean package        # -> lending-e2e-test-service/target/lending-e2e-test.jar

cd lending-e2e-test-service
export E2E_BATCH_PASSWORD=...  E2E_ADMIN_PASSWORD=...         # credentials: environment only

java -jar target/lending-e2e-test.jar guard    --profile=local   # prints ALLOWED / REFUSED and exits
java -jar target/lending-e2e-test.jar dry-run  --profile=local   # read-only: connectivity, product limits, plan, estimate
java -jar target/lending-e2e-test.jar run      --profile=local   # the real thing
java -jar target/lending-e2e-test.jar teardown E2E-261101093000-A1B --profile=local
java -jar target/lending-e2e-test.jar serve    --profile=local   # optional REST trigger on :8090
```

Local stack: `docker compose up --build` at the repo root (lending 8080, batch 8081, admin 8082; compose credentials are
`batch-admin/changeme` and `admin/changeme`). Any `e2e.*` setting can be given as `--key=value`, `-De2e.key=value` or
`E2E_KEY` (dots and dashes become underscores).

Docker (no Java needed on the runner):

```bash
docker build -t lending-e2e-test lending-e2e-test-service
docker run --rm --env-file lending-e2e-test-service/.env -v "$PWD/results:/results" lending-e2e-test run --profile=sit
```

Exit codes: `0` pass, `1` a check at or above `e2e.fail-on-severity` (default HIGH) failed (NOT FOUND capabilities gate only if `e2e.fail-on-not-found=true`), `2` preflight problems (nothing
was created), `3` refused by the production guard, `4` error.

## Environments and the production guard

Profiles `local`, `dev`, `sit`, `uat` live in `src/main/resources/application-<profile>.properties` (URLs only). Anything else
is overridden by an external file (`-De2e.config=/path/file.properties`), environment variables, or CLI options, in that
order. **No credentials, URLs or thresholds are hard-coded in code.**

The suite **refuses to run against production** and has no override switch. A run is refused when the profile is not in
`e2e.guard.allowed-profiles`, when the profile / `e2e.env.name` contains a word from `e2e.guard.production-words`
(`prod`, `prd`, `live`), when any target host matches `e2e.guard.production-host-patterns`, when
`e2e.guard.allowed-host-patterns` is set and a host is not on it (recommended for UAT), or when no target is configured.
The guard runs before any network call, also for `teardown` and `serve`, and the REST trigger cannot change URLs, profile or
guard settings. Covered by `EnvironmentGuardTest`.

## Population and the scenario matrix

`src/main/resources/scenarios.csv` (override with `e2e.scenario-file=/path.csv`) is the matrix; nothing about it is in code.
`e2e.accounts` sets N (default **20**) and `e2e.cohort-a-percent` the split (default **50/50**); rows are repeated by
`weight` (0 disables a row) in round-robin per cohort. Each row names the product and any term overrides (frequency,
rate, day count, moratorium, balloon %, step-up %), the borrower **behaviour**, and the platform capabilities it relies on.

| Behaviour | What the borrower does | Covers |
|---|---|---|
| `ON_TIME` | pays each instalment on its due date | cohort A baseline, all schedule types / day counts |
| `LATE_PAY` | pays `lateDays` after each due date | DPD rises and clears every month |
| `MISS_THEN_CURE` | misses instalment `missInstalment`, clears all arrears `lateDays` later, resumes | scenarios (b) SMA0 cure, (c) SMA2 cure, (d) NPA then cure |
| `STOP_PAYING` | pays the first `payInstalments`, then stops for `observeDaysAfterLastDue` | scenario (a): SMA0 -> NPA -> sub-standard ... (disabled long row B09 reaches LOSS) |
| `SHORT_PAY_THEN_PAY` | attempts a part-instalment payment, then pays correctly | scenario (e) |
| `PART_PAY_THEN_ON_TIME` | one principal part-payment of `percent`% after instalment `afterInstalment` | reschedule |
| `FORECLOSE_AT` / `NPA_THEN_FORECLOSE` | forecloses on a due date / `forecloseAfterDays` after the first miss | scenario (f) |
| `RESTRUCTURE_AFTER_NPA` | goes NPA, then probes restructuring | scenario (g) - reported NOT FOUND |

The scenarios in the brief's list (a)-(g) are my reading of it; edit the CSV to change them. Mortgage products (HL01) are
excluded because they need a mortgage record; dry-run flags any scenario outside its product's limits.

## The simulated day

```
for each business day D from e2e.start-date:
    e2e.day.order = PAY_THEN_EOD (default)          or  EOD_THEN_PAY
      payments   : oldest-unpaid-first, exact instalment amount, value date D, Idempotency-Key <runId>-A<n>-...
      batch EOD  : e2e.batch.daily-jobs for businessDate D (+ e2e.batch.monthly-jobs on month end)
                   POST /api/v1/batch-admin/jobs/{job}/trigger {businessDate: D}  ->  poll /jobs/{job}/executions
    verify each live account (read-only)
```

The date is passed everywhere the platform accepts one (`businessDate` job parameter, `valueDate`, `foreclosureDate`);
the platform has no business-date service, so the suite owns the calendar. `e2e.accrual.path=API` and `e2e.dpd.path=API`
(default `BATCH`) call the lending-service endpoints directly instead; that is a deviation from the agreed batch-only path
and is printed under **Deviations** in the report.

### What is checked (catalogue: `src/main/resources/checks-catalogue.csv`, severity per check)

| Area | Checks |
|---|---|
| Setup / schedule | accepted creation and disbursal; schedule invariants for the account's terms (count, due dates, interest per day count, balance roll, closing zero, schedule-type rules, moratorium); account EMI = first instalment |
| Batch | trigger accepted; execution reaches COMPLETED; accrual/DPD/NPA jobs read > 0 records when eligible accounts exist; re-run for the same date does not duplicate accruals |
| Accrual (daily) | row exists per active account; amount = principal x rate / basis (independent day-count maths); principal used = test ledger; none after closure; accrued vs interest paid at closure |
| Payment | accepted at the exact amount and applied to the oldest unpaid instalment; principal/interest split = schedule; idempotent replay; short payment rejected without state change; history = what the test posted; part-payment reschedules; foreclosure closes |
| Balance / status | outstanding principal = test ledger every day; zero at closure; fully-paid -> CLOSED; legal transitions; status = bucket of independently computed DPD; ladder without skipped buckets; cured -> ACTIVE; NPA family from the threshold; OVERDUE marking; admin DPD follows the simulated date |
| Capabilities | evidence from the services' OpenAPI documents: penal interest, provisioning, NPA interest suspension, ledger, restructure, write-off, cure rule, configurable thresholds, business date -> `NOT FOUND` (or `SKIPPED` when no OpenAPI is readable) |

Schedule correctness is asserted as **properties** (and with day-count maths written independently of lending-service), not
by copying the product's algorithm, so a defect cannot hide behind an identical defect on the test side.

## Outputs (`results/<runId>/`)

| File | Content |
|---|---|
| `audit-trail.csv` | one row per account per simulated day: events, model vs system outstanding, model DPD, expected vs actual status, expected vs actual accrual, cumulative accrual, failed checks that day |
| `batch-runs.csv` | every trigger: HTTP status, final status, exit code, read/write/skip counts, elapsed, timeout |
| `checks.csv` | every failed / NOT FOUND / skipped finding with expected vs actual |
| `run-report.md` | verdict by severity, deviations, per-account outcome, check table, capabilities, labelled defaults, open questions |
| `defects.md` | the ranked defect list (CRITICAL first), each with scenarios, accounts, dates, expected/actual |
| `run-manifest.json` | run id, tagged id range, every account id, effective settings (secrets masked) |
| `cleanup-<runId>.sql` | emitted by `teardown` |

Results are files by design (the suite must not write to lending tables). A separate results schema can be added later
without touching the platform; say if you want it.

## Tagging, teardown and the read-only guarantee

* **Tagging.** `customerId = sanctionRefId = e2e.id-base + <run timestamp yyMMddHHmmss> * 1000 + index`, and every
  `Idempotency-Key` starts with the run id. One range predicate therefore finds every account and idempotency row of a run
  even without the manifest.
* **Teardown (`teardown <runId>`).** (1) Through the API it forecloses every account of the run that is not already in a
  terminal state, so nothing is left live for scheduled batch jobs. (2) lending-service has no delete API and the suite
  never writes business tables, so physical removal is **emitted as a reviewed SQL script**
  (`cleanup-<runId>.sql`, scoped by the id range and key prefix, wrapped in a transaction) for a DBA / pipeline to run on a
  non-production database. Batch execution history is not tagged per run and stays.
* **Read-only.** `AdminReadClient` has no method that issues anything but `GET`; there is no JDBC dependency at all.

## Open questions (each has a labelled, configurable default)

| # | Question | Default used | Setting |
|---|---|---|---|
| 1 | Accrual on opening or closing balance of the day? | closing (payments first) | `e2e.day.order` |
| 2 | DPD buckets and NPA cut-off | the platform's current hard-coded buckets; NPA from 91 DPD | `e2e.dpd.buckets`, `e2e.dpd.npa-from-days` |
| 3 | Payments by API or by a file/batch channel? | API (a file channel needs `AUTO_DEBIT_PROCESSING_DAILY` implemented) | - |
| 4 | Provisioning %, NPA interest suspension, penal interest | no rules exist; reported NOT FOUND | - |
| 5 | Number of accounts, start date, simulation length | 20, 2026-11-01, 800 days | `e2e.accounts`, `e2e.start-date`, `e2e.max-sim-days` |
| 6 | Money tolerances | 0.05 money, 0.01 daily accrual, 2 % accrued-vs-paid | `e2e.tolerance.*` |

## Practical notes

* **Run time.** Roughly `days x (jobs + accounts x 2)` HTTP calls. A 20-account default run covers ~770 simulated days
  (~4,600 trigger calls); cut verification with `e2e.verify.every-n-days`, shorten with `e2e.max-sim-days`, or trim
  `e2e.batch.daily-jobs`.
* **Partitions.** `interest_accrual` / `emi_transaction` partitions exist only for part of the future. If the simulated
  window leaves them, payments or accruals fail and are reported as ENV-01; keep `e2e.start-date` inside the covered range or
  extend the partitions.
* **Free-tier hosts (Render).** Cold starts are absorbed by `e2e.http.retries` / backoff; raise
  `e2e.http.request-timeout-seconds` if the first call times out.
* **Admin DPD.** The admin portal derives days-past-due from the wall clock, so under a simulated date it disagrees with the
  suite's DPD; reported as DPD-04 (switch off with `e2e.check.admin-dpd-wallclock=false`).
* **Optional hermetic run.** `mvn -f lending-e2e-test-service/pom.xml -Ptestcontainers verify` starts the repo's
  docker-compose stack with Testcontainers and runs a short slice (`FullStackIT`).

## Testing the suite itself

* `mvn test`: 33 unit tests (day counts, DPD model, schedule invariants, production guard, scenario loader, teardown SQL).
* `mock/mock_platform.py`: a stand-in for the three services used to test the tests. `--mode impl` is a healthy platform,
  `--mode stubs` mirrors today's batch behaviour, `--mode bugs` seeds defects the suite must catch. It is a development aid and
  is not part of any deliverable image.

```bash
python3 mock/mock_platform.py --mode impl &
java -jar target/lending-e2e-test.jar run --profile=local --lending.base-url=http://localhost:18080 \
     --batch.base-url=http://localhost:18081 --admin.base-url=http://localhost:18082
```
