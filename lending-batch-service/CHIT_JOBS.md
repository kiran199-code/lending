# Chit jobs in the batch service

Three DAILY tasklet jobs call the chit service (`lending-chit-service`) once each for the business date. The chit service does
the work group by group and answers with a summary; a job fails, and so holds end of day on that date, if any group could not
be finished (the message names the groups). Running it again finishes only what is left: every job is safe to repeat.

| Job | Priority | Chit endpoint | What it does |
|---|---|---|---|
| `CHIT_DEMAND_RAISE_DAILY` | 6 | `POST /api/v1/chit/batch/demand-run` | Opens every cycle whose instalment date has been reached and raises each subscriber's instalment, using advance money held. Catches up missed days. |
| `CHIT_LATE_FEE_DAILY` | 112 | `POST /api/v1/chit/batch/late-fee-run` | Charges the late fee, one day at a time, on each overdue instalment up to the date. Never charges a day twice. |
| `CHIT_DEFAULT_CHECK_DAILY` | 114 | `POST /api/v1/chit/batch/default-run` | Flags tickets that breached their group's default threshold. Only flags; recovery is manual. |

**All three are seeded switched off** (`V8__chit_daily_jobs.sql`). A failing job holds end of day for the loans too, and the chit
service is new. Switch them on one at a time from the Configuration screen (or `PUT /api/v1/batch-admin/jobs/{name}`) once the
settings below are in place and a run by hand has worked. A disabled job cannot be launched from batch-admin, so the first runs
are by hand: the admin console's Chit funds screens have a button for each, or call the three chit endpoints directly.

## Settings (environment of lending-batch-service)

| Variable | Default | Meaning |
|---|---|---|
| `CHIT_SERVICE_URL` | empty | Base URL of the chit service. **Empty means the jobs skip with a warning** (they finish successfully and do nothing), so a half-configured chit service never holds up the loans. |
| `CHIT_SERVICE_USERNAME` `CHIT_SERVICE_PASSWORD` | empty | The chit service's Basic credential (`CHIT_SERVICE_USERNAME`/`CHIT_SERVICE_PASSWORD` there). Sent only when a username is set. |
| `CHIT_SERVICE_REQUEST_TIMEOUT_SECONDS` | 240 | Long, because a run touches every group and a free-plan service can take a minute to wake. |
| `CHIT_SERVICE_MAX_ATTEMPTS` | 3 | Attempts on I/O errors and 429/502/503/504. Anything else (including 409 `BUSINESS_DATE_MISMATCH`) fails at once. |
| `CHIT_SERVICE_CONNECT_TIMEOUT_SECONDS` `CHIT_SERVICE_RETRY_BACKOFF_MILLIS` | 5, 2000 | |

Job parameter (`job_params_json`): `{"requireChitService": true}` makes an empty `CHIT_SERVICE_URL` fail the job instead of skipping it.

The chit service refuses a run whose `businessDate` is not lending-service's current business date, so a stale or misconfigured
batch cannot book a wrong day.
