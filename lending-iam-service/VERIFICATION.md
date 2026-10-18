# Verification status

This file says what was actually run against this code, what was not, and what that means for the first deployment. It describes the state when the module was delivered; re-run the Maven build before relying on any of it.

## The constraint

The environment that produced this module could not reach Maven Central. There were therefore no real Spring Boot, Spring Security, Spring Authorization Server, Spring JDBC, Flyway or JUnit jars. Everything below was done with a JDK, a PostgreSQL 16 server, Python (psycopg2) and the Jackson / SLF4J jars that happened to be on the machine. Where Spring classes were needed to compile, small hand-written **stubs** with the signatures this code uses were substituted. Stubs prove that the code is self-consistent with *my understanding* of those APIs; they do not prove that the real APIs behave or are shaped that way.

## What was run

| Check | Result | What it covers |
|---|---|---|
| Stub compile of all main and test sources (`javac -Xlint:all`) | exit 0, 3 generic-varargs warnings | Syntax, types, internal consistency of every class except `SecurityConfig` |
| Unit tests through a small JDK runner | 64 passed, 1 not runnable (see below) | Password policy and history, lockout, TOTP and recovery codes, separation-of-duties rules, decision engine (all denial codes and their order), approval rules, audit hash and canonical JSON, URL policy, AES-GCM with context binding, HTML escaping and the request guard, config glue |
| Service and repository run on a real PostgreSQL 16 | 563 checks, 0 failures, repeated runs stable | All five migrations applied in order; every service and repository on the real schema; see below |

The 65th unit test, `BcryptSecretHasherTest`, needs the real `spring-security-crypto`; the stub encoder returns a constant, so the test fails there. It has **never been run**. Everything that hashes a password in the PostgreSQL run used a test double that is not bcrypt. Bcrypt cost, timing, and the `{bcrypt}` prefix handling are therefore untested here.

### The PostgreSQL run

Each worker thread had its own connection. `Tx.run` emulates `@Transactional` (commit on success, roll back on any exception) and `Tx.runKeeping` emulates `noRollbackFor = IamException.class`; after a failed statement the transaction stays aborted as it does in PostgreSQL. It exercised, among other things:

- user lifecycle including offboarding with future-dated roles, lockout after 5 failures, password expiry and history, activation, administrator reset, MFA enrolment, use of recovery codes (each once, concurrently), MFA-required roles staying inert until enrolment;
- roles, scopes, ceilings, the decision engine against stored data, account-bound permissions, step-up;
- approvals: submit (including concurrent submits with one Idempotency-Key), approve, reject, cancel, consume (concurrent, idempotent), two-approver actions, maker ≠ checker, separation-of-duties, expiry sweep, late decision recorded as expired, on-behalf submission;
- customers: activation tokens, consent versions and withdrawal, links to a customer or an account, second-person approval;
- audit: concurrent writers, hash chain verification, refusal of `UPDATE` / `DELETE` / `TRUNCATE` by the database;
- events: ordered delivery per subject, back-off, replay, retry limit (HTTP replaced by a test sender);
- signing-key creation and rotation under concurrency, encryption-key rotation, bootstrap on first start, bootstrap recovery when nobody can administer, break-glass.

Real defects found and fixed by this run (they would have shown up in production): a race on idempotent submit, offboarding a user with a future-dated role violating a CHECK constraint, unserialised signing-key creation and rotation, one event per pass in the webhook dispatcher, and no way to obtain a first token.

## What was not run

These are the places most likely to hold defects, in the order I would worry about them:

1. **`SecurityConfig`** (the three filter chains, the `HttpSecurity` DSL, the JWT converter, CORS, the authorization-server configurer). Excluded from the stub compile because it needs the real API. It has been read, not compiled.
2. **Spring Authorization Server integration**: `IamRegisteredClientRepository` mapping, the token customizer, `JdbcOAuth2AuthorizationService` against the table in migration V5 (column names and types were written from memory of the SAS 1.3 schema and **must be compared** with `oauth2-authorization-schema.sql` in the SAS jar), refresh-token rotation, the discovery document, the JWKS endpoint serving the stored keys. SAS also rejects `localhost` as a redirect host for authorization-code requests in some versions; the seeded client uses `127.0.0.1` for that reason, but this was not confirmed against the real library.
3. **Sign-in pages** (`LoginPagesController`): the form login flow, redirect after password change and MFA enrolment, and the consent page, have not been rendered or driven in a browser.
4. **`StartupRunner`** (bootstrap, key creation at start-up) with real Spring lifecycle, and Flyway running the migrations with `iam_schema_history` in schema `iam`. The migrations were applied by `psql`, not Flyway.
5. **The Maven build**, the Docker image, the memory footprint on a 512 MB instance, and bcrypt timing.
6. **`tools/iam_token.py`**: run only against a mock authorization server, never against this service.
7. **Controllers' HTTP behaviour**: request validation annotations, status codes and JSON shapes were checked by reading and by the endpoint cross-check (72 REST endpoints in the code, 71 in the specification's appendix plus `GET /api/v1/public/consent-purposes`, which was added later; permissions per endpoint agree except where the check lives in the service, as for approve). No endpoint has been called over HTTP.
8. **Anything involving another service**: no lending, batch, admin or LCM component has been connected to IAM.
9. **Load and security testing**: none. No penetration test, no review of the filter ordering by anyone else.

## Suggested first steps on a real machine

1. `mvn -q verify` and fix whatever the compiler reports first; expect small API mismatches in `SecurityConfig`, the token customizer and the registered-client mapping.
2. Start the service on an empty database and read the Flyway log. Compare `oauth2_authorization` with the schema inside the SAS jar before anything else.
3. Run `tools/iam_token.py`, then `GET /api/v1/me`, then `GET /.well-known/openid-configuration` and `GET /oauth2/jwks`.
4. Work through the scenarios in the specification section 7 by hand once, with a second browser profile for the checker.
5. Only then follow the adoption order in the README.

## The harness

The harness, the stubs and the two helper scripts are delivered separately as `iam-verification-harness.tar.gz`. They are scaffolding for this particular environment: paths (`/home/claude`, `/opt/gradle-8.14.3/lib`) and the `su postgres` calls are hard-coded, and the stubs are not a Spring replacement. They are useful to re-run the 563 checks after changing a service or repository, and to read as executable examples of how the services are meant to be wired. They are not part of the product and should not be added to the Maven build.
