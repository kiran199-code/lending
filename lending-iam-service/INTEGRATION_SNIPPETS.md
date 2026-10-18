# IAM integration snippets (for the shared files this round deliberately does not touch)

Nothing here has been applied. Paths are relative to the repository root; the module is `lending-iam-service/`. LCM's equivalent snippets are in `lending-lcm-service/INTEGRATION_SNIPPETS.md`.

## 1. Root `pom.xml`: one line in `<modules>`

```xml
        <module>lending-iam-service</module>
```

IAM has its own Spring Boot parent and Java 17, like the batch and LCM services; nothing else in the root pom changes. (Adding the line makes `mvn -f pom.xml test` build IAM too; leave it out if you want IAM built only from its own folder.)

## 2. `render.yaml`: new service

Add under `services:` (after `lending-admin-service`, or after `lending-lcm-service`). Same database, own schema `iam`.

```yaml
  - type: web
    name: lending-iam-service
    runtime: docker
    plan: free
    region: singapore
    dockerfilePath: ./lending-iam-service/Dockerfile
    dockerContext: ./lending-iam-service
    healthCheckPath: /actuator/health
    envVars:
      - key: PORT
        value: "10000"
      - key: DB_HOST
        fromDatabase: { name: lending-db, property: host }
      - key: DB_PORT
        fromDatabase: { name: lending-db, property: port }
      - key: DB_NAME
        fromDatabase: { name: lending-db, property: database }
      - key: DB_USER
        fromDatabase: { name: lending-db, property: user }
      - key: DB_PASSWORD
        fromDatabase: { name: lending-db, property: password }
      - key: IAM_DB_SCHEMA
        value: iam
      - key: IAM_ENCRYPTION_KEY
        generateValue: true     # 32 random bytes are needed in base64; if Render's generated value is not valid base64 of 32 bytes the service refuses to start: then paste the output of  openssl rand -base64 32  and mark it sync: false. KEEP A COPY OFF-PLATFORM: losing it makes every stored secret unreadable.
      - key: IAM_ISSUER
        sync: false             # the service's own public URL, https://lending-iam-service-XXXX.onrender.com (no trailing slash); it becomes "iss", so set it before the first token is issued
      - key: IAM_BOOTSTRAP_USERNAME
        value: bootstrap
      - key: IAM_BOOTSTRAP_PASSWORD
        sync: false             # first start only; delete this variable once two named administrators exist
      - key: IAM_CORS_ORIGINS
        value: https://lending-admin-service-XXXX.onrender.com   # the browser origins that may call the API; add the portal later
      - key: IAM_BCRYPT_COST
        value: "10"             # halves sign-in CPU on the shared free instance
      - key: JAVA_TOOL_OPTIONS
        value: "-Xmx300m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
```

Notes:

* Spring Authorization Server plus bcrypt is the heaviest of the services. 300 MB of heap is a guess for a 512 MB container; watch the first week's memory graph.
* The free plan sleeps the service. Every consumer of IAM validates tokens offline against the key set it caches, so a sleeping IAM only hurts sign-in, refresh and `authz/check` calls, not already-issued tokens until they expire (15 minutes for staff, 10 for services and customers).
* `IAM_COOKIE_SECURE` stays `true` on Render (HTTPS terminates at the proxy; `IAM_FORWARD_HEADERS=framework` is the default and is what makes the issuer and redirect URLs come out as `https`).
* Nothing is added to the other services' `envVars` until a service adopts IAM (README, "Adopting it").

## 3. `CHANGELOG.md`: under `## [Unreleased]` / `### Added`

```markdown
- **Identity and access management: a new, separate service (`lending-iam-service`), not yet used by any other service.** Built from *IAM Requirements v0.1* (`docs/IAM_Requirements_v0.1.docx`, 104 requirements, 24 scenarios), phases A to D. Phase E (approval of role changes) and F are not built.
  - **`lending-iam-service`** (port 8085, Java 17, schema `iam`, Flyway history `iam_schema_history`, five migrations, 72 REST endpoints plus the OAuth/OIDC endpoints and four sign-in pages). Spring Authorization Server: authorization code with PKCE for people, client credentials for services, RS256 JWTs with keys stored AES-256-GCM-encrypted in the database (rotation keeps the old key published for 24 h). Staff and customer identities with lifecycle, password policy and history, lockout, TOTP second factor with recovery codes. 88 permissions, 26 system roles, branch / product / currency scopes, per-currency amount ceilings, 14 separation-of-duties rules, and a decision endpoint (`POST /api/v1/authz/check`) with stable denial codes. Generic maker-checker (one or two approvers, payload hash, `Idempotency-Key`, consume-once, expiry, callbacks, submit on behalf of). Customer activation, versioned consents, links to a customer or one account with second-person approval. Append-only hash-chained audit trail (the database refuses UPDATE, DELETE and TRUNCATE) with a verify endpoint. Outbox and HMAC-signed webhooks with ordered delivery, back-off and replay.
  - **First start** creates one bootstrap administrator and the public client `iam-admin-cli`; `tools/iam_token.py` obtains the first token. If nobody can administer any more, restarting with the bootstrap settings restores the bootstrap administrator (audited `BOOTSTRAP_RECOVERY`). `RUNBOOK.md` covers key rotation, loss of the encryption key and adoption.
  - **No other service calls IAM yet.** The adoption order (service accounts, `both` mode, `tokens` mode) is in the README and section 10 of the specification. **lending-service still has no security layer; its API is open.**
  - **Verification.** Unit tests of the pure rules (64 run, 1 needs the real bcrypt library) and a run of every service and repository against PostgreSQL 16 (563 checks, including concurrent approvals, consumption, activation, recovery-code use, key rotation and audit writers). **Not run:** the Maven build, `SecurityConfig` and the Authorization Server wiring, the sign-in pages, Flyway itself, bcrypt, and any HTTP call. See `lending-iam-service/VERIFICATION.md`.
  - **Deploy:** independent of the other services; set `IAM_ENCRYPTION_KEY`, `IAM_ISSUER` and the bootstrap settings before the first start.
```

(The 24-scenario figure is the number of rows in section 16 of the specification; count them again if you edit it.)

## 4. `README.md`

Services table, after the LCM or admin row:

```markdown
| `lending-iam-service` | Identity and access management: sign-in and tokens (OAuth2/OIDC), users, roles and permissions, scopes and ceilings, maker-checker approvals, customer identities and consent, audit. Own schema `iam` in the shared database; no other service calls it yet | 8085 / 10000 |
```

Docs table:

```markdown
| `docs/IAM_Requirements_v0.1.docx` | Identity and access management requirements: 104 requirements with phase and acceptance criterion, 24 scenarios, data model, API appendix, permission catalogue, roles, separation-of-duties rules, approval types |
```

Run section, one line: `#   IAM service      http://localhost:8085   (bootstrap login set by IAM_BOOTSTRAP_USERNAME / IAM_BOOTSTRAP_PASSWORD; first token: python3 lending-iam-service/tools/iam_token.py)`

Known-limits paragraph, append: "IAM has not had a Maven build and no service uses it yet; lending-service's API is still open."

## 5. Environment variables

The full list, with defaults, is `src/main/resources/application.yml`. The ones to set deliberately:

| Variable | Default | Meaning |
|---|---|---|
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` `DB_PASSWORD`, `IAM_DB_SCHEMA` | localhost / 5432 / lending / lending / lending, `iam` | shared Postgres, own schema |
| `PORT` | 8085 | |
| `IAM_ENCRYPTION_KEY` | none | **required**, base64 of 32 bytes; the service will not start without it |
| `IAM_ENCRYPTION_KEY_PREVIOUS` | none | set during key rotation only |
| `IAM_ISSUER` | `http://localhost:8085` | public base URL, becomes `iss`; must match what consumers configure |
| `IAM_BOOTSTRAP_USERNAME` / `IAM_BOOTSTRAP_PASSWORD` / `IAM_BOOTSTRAP_EMAIL` | none | first start and recovery only |
| `IAM_CORS_ORIGINS` | `http://localhost:5173` | allowed browser origins |
| `IAM_BCRYPT_COST` | 11 | 10 on the free plan |
| `IAM_STAFF_ACCESS_SECONDS` / `IAM_STAFF_REFRESH_SECONDS` | 900 / 28800 | staff token lifetimes |
| `IAM_SERVICE_ACCESS_SECONDS` / `IAM_CUSTOMER_ACCESS_SECONDS` / `IAM_CUSTOMER_REFRESH_SECONDS` | 600 / 600 / 1800 | |
| `IAM_PW_MIN_LENGTH` `IAM_PW_MIN_CLASSES` `IAM_PW_HISTORY` `IAM_PW_MAX_AGE_STAFF` `IAM_PW_MAX_AGE_CUSTOMER` | 12 / 3 / 5 / 90 / 365 | password policy |
| `IAM_LOCK_FAILURES` / `IAM_LOCK_MINUTES` | 5 / 15 | lockout |
| `IAM_STEPUP_MINUTES` | 15 | window for a recent second factor |
| `IAM_WEBHOOK_ALLOW_HTTP` | `false` | `true` only on a developer machine |
| `IAM_SWEEP_CRON` | every minute | expiry and webhook sweep; `-` disables |

## 6. Things to know before you apply

* **Port.** 8085 does not collide with the others (lending 8080, batch 8081, admin 8082, LCM 8084).
* **Adoption touches other modules later, not now.** Each consumer needs the resource-server dependency, `issuer-uri` pointing at `IAM_ISSUER`, a mapping from the `perms` claim to authorities, and a service account created in IAM. LCM and the other modules already name their permission codes.
* **Shared Postgres.** IAM's Flyway runs in schema `iam` with history table `iam_schema_history`; it cannot collide with lending's `flyway_schema_history`. The database role needs CREATE on the database for the first start.
* **Audit and the shared database.** The audit table's immutability triggers are in schema `iam`; a database owner can still drop them. The hash chain is what makes tampering detectable, not the triggers.
