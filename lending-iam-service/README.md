# lending-iam-service

Identity and access management for the lending platform: who is signing in, what they may do, who must approve it, and the record of all of it. A separate Spring Boot service with its own schema (`iam`), its own migrations and its own deployment. It does not read or write any lending, accounting, batch or LCM table, and none of them is wired to it yet (see *Adopting it* below).

Specification: `docs/IAM_Requirements_v0.1.docx` (requirements IAM-FR-xxx, scenarios, data model, API appendix).
Runbook: `RUNBOOK.md`. Permission catalogue: `IAM_PERMISSIONS.txt` (generated from the migrations).

## What it does

| Area | What is built (phases A to D) |
|---|---|
| Tokens | OAuth 2.1 / OIDC authorization server (Spring Authorization Server). Authorization code + PKCE for people, client credentials for services. RS256 JWTs, keys stored encrypted in the database, rotation with the old key still published for 24 h, discovery document and JWKS. |
| Staff | Users with a lifecycle (created, activated by a temporary password, suspended, reinstated, locked, expired, offboarded), password policy and history, lockout, TOTP second factor with recovery codes, administrator reset. |
| Access | A catalogue of 88 permissions, roles, branch / product / currency scopes, per-currency amount ceilings, 14 separation-of-duties rules, and a decision endpoint (`POST /api/v1/authz/check`). |
| Maker-checker | Approval types, submit / approve / reject / cancel / consume, one or two approvals, expiry, payload hash, callbacks, "submit on behalf of" for services. |
| Customers | Activation by one-time link, mandatory and optional consents with versions and an immutable history, links to a customer or to a single account (owner, co-borrower, guarantor, delegate) with approval by a second person, account-bound decisions. |
| Audit | Append-only, hash-chained trail with a verify endpoint; the database refuses `UPDATE`, `DELETE` and `TRUNCATE`. |
| Events | Outbox and signed webhooks (HMAC-SHA256), ordered per subject, retried with back-off, replayable. |

Not built: an administrator console, the customer portal itself, role-change approval (phase E), a second IAM instance, a password-reset email flow (no mailer; resets are issued by an administrator and shown once).

## Run it

```
DB_HOST=... DB_PORT=5432 DB_NAME=lending DB_USER=... DB_PASSWORD=...        # shared Postgres; the service creates schema "iam"
IAM_ENCRYPTION_KEY=$(openssl rand -base64 32)                               # required; the service will not start without it
IAM_ISSUER=https://lending-iam.example.com                                  # public base URL, becomes "iss"
IAM_BOOTSTRAP_USERNAME=admin  IAM_BOOTSTRAP_PASSWORD='...'                  # first start only; see RUNBOOK.md
mvn spring-boot:run          # port 8085 (PORT overrides)
```

All settings are in `src/main/resources/application.yml`; every one has an environment variable and a default taken from the specification. `GET /actuator/health` is public.

On Render's free plan (512 MB) the Dockerfile caps the heap at 60 % of the container and uses the serial collector. Sign-in is dominated by bcrypt: at the default cost 11 a sign-in takes a fraction of a second on a normal CPU and noticeably longer on a shared one; `IAM_BCRYPT_COST=10` halves it.

## Getting the first token

There is no console yet, and the admin API wants a bearer token. The first start creates a public client `iam-admin-cli` (authorization code + PKCE, redirect `http://127.0.0.1:8765/callback` only). Use:

```
TOKEN=$(python3 tools/iam_token.py --issuer https://lending-iam.example.com)
curl -H "Authorization: Bearer $TOKEN" https://lending-iam.example.com/api/v1/me
```

The script opens the sign-in page, receives the code on the loopback port, proves possession with PKCE and prints the access token (15 minutes). The bootstrap administrator holds roles that require a second factor, so the first browser sign-in goes through enrolment (scan the secret into an authenticator app, confirm one code, keep the recovery codes).

## Endpoints

72 REST endpoints under `/api/v1` plus the standard OAuth/OIDC endpoints and the sign-in pages (`/login`, `/change-password`, `/enrol`, `/consent`). Each endpoint names the permission it needs; the full list with request and response shapes is Appendix E of the specification. Groups: `me`, `users`, `roles` / `permissions` / `sod-rules`, `clients`, `keys`, `authz/check`, `approval-types` / `approvals`, `customers` / `customer-links` / `consent-purposes`, `webhooks`, `audit`, and `public/activate`, `public/consent-purposes` (the only two without a token).

Errors have one shape: `{timestamp, status, error, code, message, correlationId, details?}`. Send `X-Correlation-Id` to choose the id; it is echoed and written to the audit trail.

## Tokens

Access-token claims (read from the database at issue and at every refresh):

| Claim | Meaning |
|---|---|
| `typ` | `STAFF`, `CUSTOMER` or `SERVICE`. Services reject anything else (an ID token has no `typ`). |
| `roles`, `perms` | Role codes and the permission codes they grant. |
| `uid`, `customer_id` | People only. |
| `branches`, `products`, `currencies` | Scopes. **Absent means unrestricted** for that dimension; `"*"` is kept as written. |
| `amr`, `mfa_at` | `["pwd"]` or `["pwd","otp"]`; epoch seconds of the last second-factor check. Permissions flagged for step-up need `mfa_at` within 15 minutes. |

Amount ceilings are not in the token (they depend on currency and amount): a service asks `POST /api/v1/authz/check` with the subject, permission, branch, product, currency and amount. The answer is `{allowed, code, stepUpRequired, limit}`; the codes, in evaluation order, are `USER_NOT_ACTIVE`, `NOT_GRANTED`, `SCOPE_BRANCH`, `SCOPE_PRODUCT`, `SCOPE_CURRENCY`, `AMOUNT_ABOVE_LIMIT`, `ACCOUNT_NOT_LINKED`, `STEP_UP_REQUIRED`.

Ceilings: each role may carry a ceiling per permission and currency (or `*`). When several roles grant a permission the greatest ceiling applies; a role with no ceiling for the call's currency lifts the ceiling altogether. Ceilings can be set on system roles; their permissions cannot be edited.

## Adopting it

Nothing in this repository calls IAM yet. The intended order, one service at a time, is described in section 10 of the specification: (1) deploy IAM, create two named administrators, suspend the bootstrap account; (2) create a service account per consumer (`SVC_LENDING`, `SVC_BATCH`, `SVC_ADMIN`, `SVC_PORTAL`, `SVC_LCM`); (3) in the consumer add the resource-server dependency with `issuer-uri` set to `IAM_ISSUER`, map `perms` onto authorities, and run in `both` mode (Basic and bearer accepted) while watching the audit trail; (4) switch to `tokens`. **lending-service currently has no security layer at all: its API is open.** Adopting IAM there is the first real access control it gets.

LCM and the other modules already name their permission codes (`lcm.facility.create`, `loan.account.disburse`, …); all are in the catalogue.

## Layout

```
src/main/java/com/bank/lending/iam/
  config/     Spring wiring: security chains, authorization server, token claims, registered clients, settings
  web/        controllers, error handler, sign-in pages, request guard
  user/ access/ client/ approval/ customer/ events/ keys/ audit/ auth/   services and repositories per area
  domain/     pure rules (password policy, lockout, TOTP, separation of duties, decision engine, approval rules, URL policy)
  crypto/     AES-256-GCM for secrets at rest
src/main/resources/db/migration/   V1 core, V2 RBAC (generated), V3 approvals and events (generated), V4 customers and consent, V5 authorization-server table
tools/        catalogue.js + gen-migrations.js (the catalogue is data: edit, regenerate V2/V3), iam_token.py (first token)
```

The permission catalogue, roles, separation-of-duties rules and approval types live in `tools/catalogue.js`; `node tools/gen-migrations.js` regenerates the seed parts of V2 and V3. Once a migration has been applied, change the catalogue with a new migration, not by editing V2 or V3.

## Verification status

What was run and what was not is stated plainly in `VERIFICATION.md`. In short: the pure rules and the glue have unit tests; every service and repository was run against a real PostgreSQL 16 (563 checks, including concurrent approvals, consumption, activation, recovery-code use and audit writers). **The Spring wiring, the security filter chains, the Authorization Server integration, the sign-in pages and the Maven build have not been run** in the environment that produced this code (no access to Maven Central).
