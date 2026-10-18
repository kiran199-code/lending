# lending-e2e-test-service

An end-to-end **lending lifecycle test suite** for the lending platform. It is a separate, independently built and
deployed project: its own `pom.xml`, executable jar, Docker image and release cycle. It talks to the platform **only over
HTTP** and is not a module of the root reactor, not a dependency of any production module, and is never packaged into a
lending artifact.

What one run does, in plain words:

1. Opens N test loan accounts (default **20**), half "good" borrowers (cohort A, must end CLOSED) and half "stress"
   borrowers (cohort B: missed payments, NPA paths, cures, foreclosure), spread over every product / schedule type /
   day-count in a data-driven scenario matrix.
2. Walks a **simulated calendar one business day at a time** on the platform's own **business date**. It sets the business date to
   the start date, then each day the borrowers pay (through the lending API with the business date as value date) and the suite runs
   the **real end of day** through the batch service (`POST /batch-admin/eod/run`: the daily jobs for that date, then the date moves
   to the next day), **polled until it finishes** (no fixed sleeps).
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
java -jar target/lending-e2e-test.jar secured-dry-run --profile=local   # SECURED flow: read-only checks (needs the LCM service)
java -jar target/lending-e2e-test.jar secured         --profile=local   # SECURED flow: collateral, facility, loan, disbursal, EOD days
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

**Existing products only, and one bad account never stops the rest.** Before preflight the suite reads the product list and fits the plan to
it: an account whose product (e.g. TL01, BB01, AB01, SU01, EM01) does not exist, is inactive or is a mortgage product moves to the closest
existing active unsecured product (its day count, moratorium, balloon and step-up overrides are dropped) - or is left out when
`e2e.products.fallback=SKIP`; amounts, tenures, rates and frequencies outside a product's limits are moved inside them. Every change is printed
as `ADAPT` in the log and listed under deviations in `run-report.md`. If creating or disbursing one account fails (`SETUP-01/02/03` in
`checks.csv` / `defects.md`), that account is marked failed and the run carries on with the others. To exercise every schedule type, create the
sample products first (admin portal: Maintenance -> Create base products).

Exit codes: `0` pass, `1` a check at or above `e2e.fail-on-severity` (default HIGH) failed (NOT FOUND capabilities gate only if `e2e.fail-on-not-found=true`), `2` preflight problems that make a run pointless - a service unreachable, a batch job disabled, the business date wrong, or no usable product at all (nothing
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
    check the platform is on D and OPEN (BDT-01); if it is behind, move it forward
    e2e.day.order = PAY_THEN_EOD (default)          or  EOD_THEN_PAY
      payments   : oldest-unpaid-first, exact instalment amount, value date D, Idempotency-Key <runId>-A<n>-...
      end of day : e2e.eod.mode = EOD_RUN (default)
                     month-end only: e2e.batch.monthly-jobs, triggered one by one for D (they are not in the EOD chain)
                     POST /api/v1/batch-admin/eod/run -> poll GET /eod/status until the run for D has finished
                       (batch service: start EOD in lending-service, run every enabled DAILY job for D, advance to D+1)
                     BAT-05 run ADVANCED; BAT-02 per job; BDT-02 platform is now D+1 and OPEN; BDT-03 admin shows the same date
                   e2e.eod.mode = JOBS_THEN_ADVANCE
                     e2e.batch.daily-jobs (+ monthly on month end) triggered one by one for D as before,
                     then lending-service POST /business-date/eod/start and /advance
    verify each live account (read-only)
```

**Business date.** lending-service owns the date; the suite drives it through the public APIs and never fakes it
(`e2e.business-date.mode=MANAGED`, the default). Before anything is created the suite sets the date to `e2e.start-date`
(`PUT /api/v1/business-date`). The date only moves forward, so the start date must not be earlier than the platform's current
business date; preflight says so (use the current date, a later date, or run lending-service with
`BUSINESS_DATE_ALLOW_BACKWARD_SET=true` and set it back). Value dates after the business date are rejected by lending-service, which
is why payments, accruals and foreclosures are always dated on the business date. The admin portal's days-past-due is measured at the
business date, so after end of day (when the date is D+1) the suite expects the DPD at D+1 (DPD-04).

`e2e.business-date.mode=OFF` restores the old behaviour (the suite only passes dates); it needs lending-service to run with
`BUSINESS_DATE_ENFORCE_NO_FUTURE=false` and is printed under **Deviations**. `e2e.accrual.path=API` and `e2e.dpd.path=API`
(default `BATCH`) call the lending-service endpoints directly instead of the batch jobs; they need `e2e.eod.mode=JOBS_THEN_ADVANCE`
(the EOD run would execute those jobs itself) and are printed under **Deviations**.

### What is checked (catalogue: `src/main/resources/checks-catalogue.csv`, severity per check)

| Area | Checks |
|---|---|
| Setup / schedule | accepted creation and disbursal; schedule invariants for the account's terms (count, due dates, interest per day count, balance roll, closing zero, schedule-type rules, moratorium); account EMI = first instalment |
| Batch | trigger accepted; execution reaches COMPLETED; accrual/DPD/NPA jobs read > 0 records when eligible accounts exist; re-run for the same date does not duplicate accruals; end-of-day run completes and advances the date (BAT-05) |
| Business date | platform is on the simulated day and OPEN before each day (BDT-01); end of day moves it to the next day (BDT-02); the admin portal shows the same date (BDT-03) |
| Accrual (daily) | row exists per active account; amount = principal x rate / basis (independent day-count maths); principal used = test ledger; none after closure; accrued vs interest paid at closure |
| Payment | accepted at the exact amount and applied to the oldest unpaid instalment; principal/interest split = schedule; idempotent replay; short payment rejected without state change; history = what the test posted; part-payment reschedules; foreclosure closes |
| Balance / status | outstanding principal = test ledger every day; zero at closure; fully-paid -> CLOSED; legal transitions; status = bucket of independently computed DPD; ladder without skipped buckets; cured -> ACTIVE; NPA family from the threshold; OVERDUE marking; admin DPD follows the platform business date |
| Secured flow | SEC-01..12: collateral DRAFT then ACTIVE, early activation refused, facility maker-checker, allocation and cover, disbursal refused without cover and accepted with it, facility utilised = test-ledger outstanding every day, nothing utilised after closure, collateral stays ACTIVE (secured flow only) |
| Capabilities | evidence from the services' OpenAPI documents: penal interest, provisioning, NPA interest suspension, ledger, restructure, write-off, cure rule, configurable thresholds, business date -> `NOT FOUND` (or `SKIPPED` when no OpenAPI is readable) |

Schedule correctness is asserted as **properties** (and with day-count maths written independently of lending-service), not
by copying the product's algorithm, so a defect cannot hide behind an identical defect on the test side.

## The secured flow (collateral and facility first)

Lending no longer holds collateral. The Limits and Collateral Management service (LCM) is the single source of truth, and a
MORTGAGE-type loan cannot be disbursed unless LCM reports ACTIVE collateral allocated to the loan's facility. The suite therefore has a
**separate secured flow**; the unsecured flow (`run`) is unchanged and never runs the secured scenarios.

```bash
java -jar target/lending-e2e-test.jar secured-dry-run --profile=local   # read-only: services, LCM, HL01 product, plan
java -jar target/lending-e2e-test.jar secured         --profile=local   # the real thing
```

Per account the flow does, in this order:

1. **Collateral** is registered in LCM (DRAFT). Activating it too early must be refused (SEC-02). Legal status VERIFIED, an approved valuation, the
   charge and the insurance expiry are then recorded; the collateral becomes ACTIVE (SEC-03). Valuations and facility changes are
   maker-checker: `e2e.lcm.maker` proposes and a different `e2e.lcm.checker` approves (SEC-12).
2. **Facility** (a single OVERALL facility per customer) is created and activated the same way (SEC-04).
3. **Loan** is created through lending on the secured product (HL01). Disbursal is first tried **before** the collateral is allocated and must be
   refused with `COLLATERAL_NOT_COVERED`, leaving the account PENDING_DISBURSEMENT (SEC-06).
4. The collateral is **allocated** to the facility and `collateral-cover` must report it (SEC-05). Disbursal now succeeds (SEC-07) and
   the facility shows the amount utilised and drawn with nothing left reserved (SEC-08).
5. **Simulated days** run exactly as in the unsecured flow (business date, payments, part-payments, end of day through the batch service). Every day
   the facility's utilised amount must equal the outstanding principal of the independent test ledger (SEC-09), and the collateral must still
   be ACTIVE (SEC-11). After closure or foreclosure the facility utilised must be zero (SEC-10).

Settings (all optional, shown with defaults; `E2E_` environment names follow the usual rule):

| Setting | Meaning |
|---|---|
| `e2e.lcm.base-url`, `e2e.lcm.username`, `e2e.lcm.password` | the LCM service (local profile: `http://localhost:8084`). The password comes from the environment only |
| `e2e.lcm.maker`, `e2e.lcm.checker` | the two `X-Actor` names for maker-checker; they must differ. They are typed names, not authenticated users |
| `e2e.secured.scenario-file` | `classpath:secured-scenarios.csv` (S01 on-time, S02 late, S03 part-payment, S04, S05; all on HL01 with feature COLLATERAL) |
| `e2e.secured.accounts`, `e2e.secured.cohort-a-percent`, `e2e.secured.max-sim-days` | replace the unsecured values for this flow only; a setting you give explicitly always wins |

Things to know before running it:

* **LCM judges validity against its own wall clock**, while the platform business date is simulated. A valuation cannot be dated in the future and
  insurance must expire after today, so run with `--start-date=<today>` (the helper script and the mock do) and keep the facility expiry beyond
  the last simulated day. `horizonDays=N` in a scenario limits a long mortgage to its first N days (closure is then not expected).
* The suite needs `lending.lcm.mode=live` on lending-service. With any other mode the gate refuses every secured disbursal and SEC-06/07 fail.
* The gate checks that cover **exists**, not that it is large enough, and only the same customer's collateral counts. The suite does not test
  "a second loan exceeds the facility limit" yet.
* A single OVERALL facility is used; parent/child and sub-limit hierarchies are not exercised.
* **Teardown** forecloses the loans, releases the allocations and closes the facility and collateral in LCM through its API, and emits a second script,
  `cleanup-<runId>-lcm.sql` (schema `lcm`, scoped to `<runId>-F<n>` / `<runId>-C<n>`), plus a `limit_outbox` delete in the lending cleanup script.
  If `e2e.lcm.base-url` is not set at teardown, the collateral and facility refs are printed and left for you.
* The production guard also checks the LCM host.

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

## Hosting the suite on Render (no local machine)

The repo's `render.yaml` defines a free web service `lending-e2e-test` that runs this suite in `serve` mode next to the
three platform services. You start runs and read results from a browser.

1. **Push the repo and sync the blueprint.** Render dashboard -> Blueprints -> your blueprint -> *Sync*. It builds the
   Dockerfile in this folder and starts the service. It reads the batch and admin passwords from those services'
   environment variables, and generates its own `E2E_SERVER_TOKEN`.
2. **Set two URLs** (they are public URLs, not secrets) on `lending-e2e-test` -> Environment:
   `E2E_BATCH_BASE_URL` = the lending-batch-service URL, `E2E_ADMIN_BASE_URL` = the lending-admin-service URL.
   `E2E_LENDING_BASE_URL` is pre-filled. Save; Render redeploys.
3. **Open the service URL**, paste the token (the console has boxes for accounts, max simulated days and **start date** - the start date must be the platform's current business date or later) (copy `E2E_SERVER_TOKEN` from the service's Environment tab) and press
   *Dry run*. Fix anything it reports, then *Run*. Start small: accounts `10`, max simulated days `120`.
4. **Watch** with *Refresh status* (the log tail is shown) or Render -> Logs. When it finishes, *List runs*, put the run id in
   the box and open *Report*, *Defects*, *Audit trail*, *Checks*. *Teardown* forecloses the run's open accounts and returns
   the cleanup SQL (`/runs/<runId>/cleanup`).

Things that are different on Render's free plan:

* **Sleep.** A free web service sleeps after about 15 minutes with no *inbound* request, and the suite's calls out to
  the other services do not count. While a run is active, and for 30 minutes afterwards (`e2e.server.keepalive-minutes`),
  the server pings its own `/health` through `RENDER_EXTERNAL_URL`, so it stays up. If you leave the console open that is
  enough as well.
* **Results are temporary.** The disk is wiped on restart or redeploy. Download what you need after a run. A redeploy
  during a run kills the run.
* **Guard.** The blueprint sets profile `dev` and `E2E_GUARD_ALLOWED_HOST_PATTERNS=\.onrender\.com$`, so the service can
  only ever talk to Render hosts. The trigger cannot change URLs, credentials, profile or guard settings.
* **Memory.** 512 MB; the heap is capped at 256 MB. A run of 20 accounts keeps well under that.
* **Concurrency.** One run at a time (a second start returns 409).
* **Time.** At free-tier latency the full 770-day default run can take hours. Use `max-sim-days` to shorten it.
* **Who can call it.** Everything except `/health` and the empty console page needs the `X-E2E-Token` header. Treat the
  token like a password and rotate it in the dashboard if it leaks.

API (same token header): `POST /runs[?dryRun=true]` with optional JSON such as `{"accounts":"10","max-sim-days":"120"}`,
`GET /runs`, `GET /runs/current`, `GET /runs/<id>/{report|defects|audit|checks|batch|manifest|cleanup}`,
`POST /runs/<id>/teardown`.

## Open questions (each has a labelled, configurable default)

| # | Question | Default used | Setting |
|---|---|---|---|
| 1 | Accrual on opening or closing balance of the day? | closing (payments first) | `e2e.day.order` |
| 2 | DPD buckets and NPA cut-off | the platform's current hard-coded buckets; NPA from 91 DPD | `e2e.dpd.buckets`, `e2e.dpd.npa-from-days` |
| 3 | Payments by API or by a file/batch channel? | API (a file channel needs `AUTO_DEBIT_PROCESSING_DAILY` implemented) | - |
| 4 | Provisioning %, NPA interest suspension, penal interest | no rules exist; reported NOT FOUND | - |
| 5 | Number of accounts, start date, simulation length | 20, 2026-11-01, 800 days | `e2e.accounts`, `e2e.start-date`, `e2e.max-sim-days` |
| 7 | Who drives the calendar, and how a day ends | suite sets and advances the platform business date; end of day through the batch service | `e2e.business-date.mode`, `e2e.eod.mode` |
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
* **Admin DPD.** The admin portal measures days past due at the platform business date, so DPD-04 and BDT-03 need an admin
  service with `/api/admin/business-date` and a database where lending-service has applied V13 (preflight checks both). Switch both
  off with `e2e.check.admin-dpd-wallclock=false`.
* **Use a dedicated environment.** The run moves the shared business date forward by hundreds of days. Nothing else should use that
  environment while it runs, and the batch service's nightly end of day (`batch.eod.cron`, default 01:00 India) would add a day behind
  the suite's back; the run notices (BDT-01) and stops. Point `BATCH_EOD_CRON` at a time that never fires for the run if the environment is left up
  overnight (do not use `BATCH_EOD_ENABLED=false`: that hands the daily jobs back to their own crons). The date is not reset afterwards; `teardown` forecloses at the
  business date.
* **Month-end jobs.** `EMI_BILLING_GENERATION_MONTHLY` and the other monthly jobs are not part of the end-of-day chain, so the suite
  still triggers them one by one on month end, before that day's end of day.
* **Optional hermetic run.** `mvn -f lending-e2e-test-service/pom.xml -Ptestcontainers verify` starts the repo's
  docker-compose stack with Testcontainers and runs a short slice (`FullStackIT`).

## Testing the suite itself

* `mvn test`: 51 unit tests: day counts, DPD model, schedule invariants, production guard, scenario loader, teardown SQL, end-of-day result mapping, and (new) the secured flow's scenario file, defaults, product resolution, catalogue, guard and LCM cleanup SQL. They were last run with a minimal JUnit stand-in, not Maven.
* `mock/mock_platform.py`: a stand-in for the three services used to test the tests (it keeps a business date, refuses value dates
  after it, and serves the EOD API like the real services; `MOCK_ENFORCE_NO_FUTURE=0` makes it behave like
  `BUSINESS_DATE_ENFORCE_NO_FUTURE=false`). `--mode impl` is a healthy platform (it also serves a mock LCM on 18083 for the secured flow; `MOCK_GATE=0` removes the collateral gate and `MOCK_LCM_MODE=off` simulates lending not running in live mode, so the SEC checks can be seen to fail),
  `--mode stubs` mirrors today's batch behaviour, `--mode bugs` seeds defects the suite must catch. It is a development aid and
  is not part of any deliverable image.

```bash
python3 mock/mock_platform.py --mode impl &
java -jar target/lending-e2e-test.jar run --profile=local --lending.base-url=http://localhost:18080 \
     --batch.base-url=http://localhost:18081 --admin.base-url=http://localhost:18082
```
