# Cloud Deployment Plan — Lending Platform

Covers `lending-service` (loan account/mortgage/schedule/payment REST API) and
`lending-batch-service` (standalone Spring Batch + Quartz job runner). Both are now built by the
root `pom.xml` reactor and containerized (`Dockerfile` in each service directory,
`docker-compose.yml` at the repo root for local dev). This document is the plan for running them
somewhere real.

## 1. Recommended target: AWS, managed containers (no Kubernetes)

No cloud preference was specified, so this plan targets **AWS ECS on Fargate** as the primary
path — it's the smallest amount of new operational surface for two services and matches the
"managed containers, not Kubernetes" choice already made. Section 8 gives the Azure and GCP
equivalents for the same shape, in case the target changes later; the architecture (one relational
DB, one Kafka topic, two containerized services, one CI pipeline) carries over directly.

## 2. Architecture

```
                    ┌─────────────────────────┐
   Internet ───────▶│   ALB (public)          │
                    └───────────┬─────────────┘
                                │
                    ┌───────────▼─────────────┐        ┌──────────────────────────┐
                    │  ECS Fargate service     │        │  ECS Fargate service      │
                    │  lending-service          │        │  lending-batch-service    │
                    │  (2+ tasks, awsvpc)       │        │  (2+ tasks, Quartz-       │
                    │  :8080                    │        │   clustered, safe to run  │
                    └───────────┬───────────────┘        │   >1 instance)  :8081     │
                                │                          └──────────┬────────────────┘
                                │        ┌─────────────────────────────┤
                                ▼        ▼                             ▼
                    ┌─────────────────────────┐          ┌──────────────────────────┐
                    │  RDS PostgreSQL          │          │  MSK (Kafka)              │
                    │  db=lending               │          │  topic:                   │
                    │  schema public (app)      │          │  lending.batch.job-events │
                    │  schema batch (batch svc) │          └──────────────────────────┘
                    └─────────────────────────┘
                                ▲
                                │ (read-only grant, see docker/postgres-init.sql
                                │  for the local equivalent)
                    lending-batch-service reads public.loan_account directly
```

Internal ingress for `lending-batch-service`'s admin API (`/api/v1/batch-admin/**`) goes through
an **internal-only ALB** or AWS Cloud Map service discovery, not the internet — it's an ops tool
(HTTP Basic today, see §7), not a customer-facing surface.

## 3. Networking

- One VPC, 2+ Availability Zones.
- Public subnets: the internet-facing ALB only.
- Private subnets: both ECS services, RDS, MSK. No task gets a public IP (`assignPublicIp:
  DISABLED` in the ECS service network configuration).
- NAT gateway (or VPC endpoints for ECR/S3/CloudWatch/Secrets Manager, cheaper at this scale) so
  private-subnet tasks can pull images and hit AWS APIs.
- Security groups: ALB → services on 8080/8081 only; services → RDS on 5432 only; services → MSK
  on 9092/9098 only. Nothing else is allowed to reach the database or the broker directly.

## 4. Data layer

- **RDS for PostgreSQL 16**, single instance to start (`db.t4g.medium` is a reasonable starting
  size for this workload), Multi-AZ once there's a production SLA to protect.
- One database (`lending`), two schemas — exactly the local docker-compose setup
  (`docker/postgres-init.sql`) mirrors this: `lending_app` owns `public`, `lending_batch` owns
  `batch` plus a read-only grant into `public` (it needs `loan_account` for its account-range
  partitioning, see `AbstractAccountRangeChunkJob`).
- Flyway owns all schema evolution for both services (`lending-service`'s `V1`–`V8`,
  `lending-batch-service`'s `V1`–`V3`) — never point `ddl-auto` at anything but `validate` in any
  environment past a laptop.
- `V6__create_emi_transaction_partitioned.sql` previously failed on a fresh database (its unique
  index omitted the partition column `value_date`); fixed — all of V1–V8 now apply cleanly to a
  fresh PostgreSQL 16.

## 5. Kafka

Only `lending-batch-service` currently produces to Kafka (`lending.batch.job-events`, job
completion telemetry — see `KafkaJobEventBridge`). `lending-service` has no broker integration
today; its "event" mechanism is the `ledger_outbox` DB table plus `LedgerGatewayClient`, which is
still the `LoggingLedgerGatewayClient` stub (always fails, entries stay `PENDING`). **That needs a
real implementation before disbursements actually reach a ledger in any environment, cloud or
not** — this plan doesn't manufacture connectivity, that's an existing product gap.

Options for hosting the broker itself:

- **Amazon MSK Serverless** — no broker sizing/patching, scales with throughput, simplest to
  operate for one low-volume topic. Recommended starting point.
- **Amazon MSK (provisioned)** — needed if/when throughput or retention requirements outgrow
  Serverless's limits, or Kafka ACLs/configs need finer control.
- **Self-managed Kafka on EC2/ECS** — no reason to do this at this scale; only worth it if there's
  an existing Kafka platform team already running one.

## 6. Container registry & images

- **ECR**, one repository per service (`lending-service`, `lending-batch-service`).
- Images are built by CI (§9), not locally — tagged with the git SHA (immutable, traceable) and
  `latest` (convenience only, task definitions always deploy the SHA tag).
- Both `Dockerfile`s are multi-stage (Maven build stage, `eclipse-temurin` JRE runtime stage) —
  the shipped image never contains the JDK, Maven, or source.

## 7. Secrets

**AWS Secrets Manager**, referenced directly from the ECS task definitions
(`deploy/aws/ecs/*.json`, the `secrets` block) — values are injected as environment variables at
container start, never baked into the image or committed to the repo:

| Secret | Used by |
|---|---|
| `lending-service/db-url`, `db-user`, `db-password` | `lending-service` |
| `lending-batch-service/db-host`, `db-name`, `db-username`, `db-password` | `lending-batch-service` |
| `lending-batch-service/kafka-bootstrap` | `lending-batch-service` |
| `lending-batch-service/admin-username`, `admin-password` | `lending-batch-service`'s admin API (`BATCH_ADMIN_USERNAME`/`PASSWORD`) — HTTP Basic today; putting this behind the internal ALB (§2) plus these Secrets-Manager-sourced credentials is the near-term bar, a real identity provider (SSO/OIDC) is the longer-term one |

## 8. Options comparison — Azure and GCP equivalents

Same architecture, different managed-service names, in case the target isn't AWS:

| Concern | AWS (this plan) | Azure | GCP |
|---|---|---|---|
| Compute | ECS on Fargate | Azure Container Apps (or App Service for Containers) | Cloud Run |
| Container registry | ECR | Azure Container Registry | Artifact Registry |
| Postgres | RDS for PostgreSQL | Azure Database for PostgreSQL – Flexible Server | Cloud SQL for PostgreSQL |
| Kafka | MSK / MSK Serverless | Event Hubs (Kafka-compatible endpoint) or Confluent Cloud on Azure Marketplace | Confluent Cloud on GCP Marketplace, or Pub/Sub if the Kafka API itself isn't a hard requirement |
| Secrets | Secrets Manager | Key Vault | Secret Manager |
| Ingress | ALB | Azure Front Door / Application Gateway | Cloud Load Balancing |
| CI/CD identity | GitHub OIDC → IAM role | GitHub OIDC → Azure AD federated credential | GitHub OIDC → Workload Identity Federation |
| Logs/metrics | CloudWatch (+ optional AMP/Grafana) | Azure Monitor / Log Analytics | Cloud Logging / Cloud Monitoring |

Cloud Run is worth calling out specifically: both services are stateless HTTP containers with no
special networking needs beyond reaching Postgres/Kafka, which is exactly Cloud Run's sweet spot —
if GCP becomes the target, Cloud Run is a smaller lift than the AWS ECS setup here, not a bigger
one.

## 9. CI/CD

Already in the repo:

- **`.github/workflows/ci.yml`** — runs on every push/PR, `mvn clean verify` across both modules
  from the root reactor. This is where the actual build happens for real — GitHub's runners have
  real Maven Central access.
- **`.github/workflows/deploy.yml`** — on push to `main`: build both Docker images, push to ECR,
  render each task definition with the new image tag, deploy to ECS, wait for service stability.
  Gated behind a `production` GitHub Environment (wire up required reviewers there for a manual
  approval step before deploy).

One-time setup before `deploy.yml` will run successfully:

1. Create an IAM OIDC identity provider for `token.actions.githubusercontent.com` (if the AWS
   account doesn't already have one).
2. Create an IAM role trusted by that provider, scoped to this repo, with permissions for
   ECR push/pull and `ecs:RegisterTaskDefinition` / `ecs:UpdateService` / `ecs:DescribeServices` —
   no long-lived AWS access keys anywhere in GitHub.
3. Create the two ECR repositories, the ECS cluster, and the two ECS services (one-time, via
   console/CLI/Terraform — not something CI should do on every run).
4. Fill in the `REPLACE_WITH_*` placeholders in `deploy/aws/ecs/*.json` with real ARNs/region.
5. Set repository variables `AWS_REGION`, `AWS_ACCOUNT_ID`, `ECS_CLUSTER` and secret
   `AWS_OIDC_ROLE_ARN` (Settings → Secrets and variables → Actions).

## 10. Provisioning the infrastructure itself

Two reasonable options, pick based on how much of this needs to be reproducible/reviewable vs.
how fast you need the first environment up:

- **Terraform (recommended for anything past a first proof-of-concept)** — VPC, RDS, MSK, ECR,
  ECS cluster/services/task defs, IAM roles, Secrets Manager entries, ALB, all as reviewable,
  versioned code, in a new `infra/` directory in this repo. Not included here since it needs your
  actual account ID, VPC CIDR choices, and sizing decisions to be anything but a guess — happy to
  write it once those are known.
- **AWS CLI / Console, one-time** — faster to get a first real environment running, harder to
  reproduce or audit later. Reasonable for validating this whole plan before investing in
  Terraform.

## 11. Phased rollout

1. **Local validation** (no cloud yet): `docker compose up --build` at the repo root — proves the
   images build, the two services actually talk to Postgres/Kafka/each other, before any cloud
   spend.
2. **Foundational infra**: VPC, RDS, MSK, ECR, Secrets Manager entries (§3–7), via whichever
   option from §10.
3. **First manual deploy**: register the task definitions by hand (`aws ecs
   register-task-definition --cli-input-json file://deploy/aws/ecs/lending-service-taskdef.json`
   after filling in placeholders), create the two ECS services pointed at them, confirm both come
   up healthy behind the ALB and can reach RDS/MSK.
4. **Wire up CI/CD** (§9) so subsequent deploys are `git push` instead of manual CLI calls.
5. **Observability & cutover**: CloudWatch dashboards/alarms on ECS service health, RDS
   connections, and (once `LedgerGatewayClient` has a real implementation) ledger outbox
   `PENDING`/`FAILED` counts — that queue depth is the single most important business metric to
   alert on, since a stuck ledger integration currently fails silently into retries. Only route
   real traffic once alarms exist for it.

## 12. Known gaps to close before this is production-traffic-ready

Carried over from earlier validation work, restated here because they block a *real* cloud
deployment, not just a local one:

- ~~`V6` migration fails on a fresh Postgres~~ — fixed (see §4).
- `LedgerGatewayClient` has no real implementation — disbursement/EMI/foreclosure ledger postings
  will queue in `ledger_outbox` forever in any environment until one exists.
- The MORTGAGE-account disbursement guard (`mortgage.charge_status = REGISTERED` before
  disbursing) is enforced only in `LoanAccountService.disburse()`, not by a database constraint —
  worth a defense-in-depth `CHECK`/trigger given this now runs as a multi-service system rather
  than a single app with one write path.

---

## 13. Render sandbox and the admin portal (added 10 October 2026)

The AWS plan above is the production target. For a free try-it environment the repo also ships a Render blueprint (`render.yaml`): one free PostgreSQL database and three Docker web services (`lending-service`, `lending-batch-service`, `lending-admin-service`) in the Singapore region, talking to the database over Render's private network.

| What | URL |
|---|---|
| Lending API | https://lending-service-5tqm.onrender.com |
| Lending Swagger UI | https://lending-service-5tqm.onrender.com/swagger-ui.html |
| Admin portal | https://lending-admin-service-<suffix>.onrender.com (suffix assigned by Render) |
| Admin Swagger UI | .../swagger-ui.html on the admin host |

Admin login: user `admin`, password = `ADMIN_PASSWORD` in the service's Environment tab. To let the Configuration screen edit batch job settings, set `BATCH_SERVICE_URL` on the admin service to the batch service's public URL (the blueprint copies the batch admin password automatically). Free-tier trade-offs: services sleep after ~15 minutes idle, the database is deleted after 30 days, there is no Kafka (batch events are dropped), and each JVM heap is capped at 256 MB. The admin service uses a read-only database session; for a stricter setup run `lending-admin-service/db/create-readonly-role.sql` and point `DB_USER` at that role. Loan products (migration V10, `/api/v1/loan-products`) are maintained from the admin portal's Products screen, which saves through `lending-service` (`LENDING_SERVICE_URL`, preset in the blueprint); redeploy `lending-service` so Flyway applies V10 and V11 (day counts, schedule types and parameters). The batch service's database pool is 20 connections on this setup (`DB_POOL_SIZE`), with 3 Quartz threads (`QUARTZ_THREAD_COUNT`); free Postgres allows about 97 connections across the three services. Business date and end of day (added 12 October 2026): lending-service owns the business date (migration V13, seeded with today in India time on first start) and the batch service runs a nightly end of day (`BATCH_EOD_ENABLED`, `BATCH_EOD_CRON`, default 01:00 India) that runs the daily jobs for that date and then advances it. The batch service calls lending-service (`LENDING_SERVICE_URL`, set in the blueprint; V12 `benchmark_rate` is applied by the same redeploy). The free-tier batch service sleeps, so the nightly run may be missed: call `POST /api/v1/batch-admin/eod/run` on demand and check `GET /api/v1/business-date`. For AWS, trigger end of day from one scheduler (for example EventBridge calling `POST /eod/run`) rather than relying on the in-process cron of every task, and keep `BUSINESS_DATE_ENFORCE_NO_FUTURE` at its default `true`. Start from scratch (added 12 October 2026): the admin portal's Maintenance screen can create the six base products and clear all data; lending-service does the work and refuses the clear unless `LENDING_RESET_ENABLED=true`. The blueprint sets it to `true` for the sandbox; leave it unset on any environment whose data matters. Carry-over to AWS: add the admin service as a third Fargate service behind the internal ALB and a read-only RDS role, with SSO in front of it instead of the shared Basic login.
