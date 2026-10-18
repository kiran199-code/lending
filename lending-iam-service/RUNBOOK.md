# lending-iam-service: runbook

Procedures for the people who run the service. Commands use `$IAM` for the public base URL and `$TOKEN` for an administrator's access token (`TOKEN=$(python3 tools/iam_token.py --issuer $IAM)`; it lasts 15 minutes).

Procedures marked **(emergency SQL)** change the database directly. They are written from the schema and have not been rehearsed; take a backup first and rehearse them on a copy.

## 1. First start

1. Create the settings on the service: `IAM_ENCRYPTION_KEY` (`openssl rand -base64 32`; **keep a copy outside the platform**, see section 6), `IAM_ISSUER` (the public https address, exactly as consumers will configure it), `IAM_CORS_ORIGINS`, and the bootstrap pair `IAM_BOOTSTRAP_USERNAME` / `IAM_BOOTSTRAP_PASSWORD` (the password must meet the policy: 12+ characters, three of four character classes, not containing the name).
2. Start the service. It creates schema `iam`, applies V1 to V5, creates the signing key, the bootstrap administrator (flagged in the audit trail) and the command-line client `iam-admin-cli`.
3. Check `$IAM/actuator/health` (UP) and `$IAM/.well-known/openid-configuration` (the `issuer` must equal `IAM_ISSUER`; if it shows `http://`, the forwarded-header setting is wrong and tokens will carry the wrong issuer).
4. Run `python3 tools/iam_token.py --issuer $IAM`. Sign in as the bootstrap user. Its roles require a second factor, so the page leads to enrolment: add the secret to an authenticator app, confirm one code, **store the recovery codes**.
5. `curl -H "Authorization: Bearer $TOKEN" $IAM/api/v1/me` should list the three identity roles.

## 2. Replace the bootstrap account with named administrators

The three identity roles are deliberately split by separation-of-duties rules (whoever creates users cannot grant access, whoever grants access cannot audit). Only the bootstrap account holds them together. Create three people, then retire it:

1. `POST /api/v1/users` `{"username":"asha.k","kind":"STAFF","displayName":"Asha K","email":"..."}` three times. The response contains a one-time temporary password (valid 24 h); hand it over out of band.
2. `PUT /api/v1/users/{id}/roles` `{"roles":[{"roleCode":"IAM_USER_ADMIN"}]}` for the first, `IAM_ACCESS_ADMIN` for the second, `IAM_SECURITY_ADMIN` for the third. Add an `AUDITOR` to somebody who holds none of the three (the rules refuse the combination).
3. Each person signs in, changes the temporary password and enrols the second factor.
4. Check that each can do their part, then `POST /api/v1/users/{bootstrapId}/suspend` (as one of the new administrators; nobody can suspend themselves), and **remove the two bootstrap settings** from the platform.

Keep at least two people in `IAM_USER_ADMIN` and two in `IAM_ACCESS_ADMIN` so one absence does not block you.

## 3. Day to day

- **New staff member**: create the user, assign roles (and scopes if they are limited to branches, products or currencies), hand over the temporary password. The first sign-in forces a new password.
- **Leaver**: `offboard`. It ends every role, withdraws the pending approvals they made, revokes their tokens and (for customers) their links. A username is never reused.
- **Suspected account misuse**: `suspend` first (tokens stop working at once; a refresh token cannot be used), investigate with `GET /api/v1/audit?actor=...`, then reinstate or offboard.
- **Locked user**: locks release themselves after 15 minutes; `unlock` releases earlier. **Lost authenticator**: `reset-mfa`, after confirming identity; the user enrols again at the next sign-in, and until then roles that require a second factor grant nothing.
- **Forgotten password**: `reset-password` issues a new temporary password (valid 24 h, shown once) and ends their sessions. There is no e-mail flow.
- **Service account**: `POST /api/v1/clients` with `kind: CONFIDENTIAL`, `grantTypes: ["client_credentials"]` and one or more `SERVICE` roles. The secret is shown once; put it in the platform's secret store. `rotate-secret` returns a new one and the old one keeps working for 30 minutes so the consumer can be redeployed. `disable` stops the account at once and removes its issued authorizations.
- **Interactive client** (a console or portal): `kind: PUBLIC`, `grantTypes: ["authorization_code"]`, exact `https` redirect addresses (no wildcards). For local development use `http://127.0.0.1:<port>/...`; the authorization server does not accept `localhost` in the redirect of a request.

## 4. Approvals

Seven approval types are seeded (`IAM_PERMISSIONS.txt`). A maker submits (a person, or a service in the name of a person with `onBehalfOf`), a different person holding the checker permission of the type approves within 72 h (24 h for temporary excess, 1 h for clear-data), and the consuming service then calls `consume` within 24 h with its own reference. A decision that arrives late is recorded as an expiry and reported as `ACTION_EXPIRED`. Approving needs a second factor verified in the last 15 minutes (step-up): a checker whose token is older is told `STEP_UP_REQUIRED` and signs in again.

Change a type's checker permission, expiry or callback with `PUT /api/v1/approval-types/{code}` (needs `iam.approval.manage`, a critical permission). A callback is a webhook owned by the type; its signing secret is shown once.

## 5. Signing keys

Tokens are signed with the one ACTIVE key; retired keys stay in the key set (public half only) for 24 h so tokens issued before the rotation still verify, then the private half is dropped.

- **Planned rotation** (every 90 days is reasonable): `POST /api/v1/keys/rotate` (needs `iam.key.manage`). Consumers that cache the key set fetch it again when they meet an unknown `kid`; with the default 30 s cache nothing else is needed.
- **Suspected compromise of a signing key**: rotate, then shorten the retired key's publication so old tokens stop verifying now **(emergency SQL)**: `UPDATE signing_key SET retire_after = now() WHERE status = 'RETIRED';` (the sweep removes it within a minute; everyone holding a token from before signs in again). Then treat the encryption key as exposed too (section 6) because the private keys are stored under it.

## 6. Encryption key (IAM_ENCRYPTION_KEY)

It encrypts, in the database: second-factor secrets, signing keys, webhook secrets. Passwords are not under it (bcrypt hashes).

- **Rotation**: set `IAM_ENCRYPTION_KEY` to the new key **and** `IAM_ENCRYPTION_KEY_PREVIOUS` to the old one, restart. At start the service re-encrypts everything it can read with the old key and logs `Encryption key rotation: n MFA secrets, n signing keys, n webhook secrets re-encrypted, n unreadable`; the audit trail gets `ENCRYPTION_KEY_ROTATED`. `unreadable` must be 0. Then remove `IAM_ENCRYPTION_KEY_PREVIOUS`. Restarting again with it still set does nothing (already under the new key), and a wrong previous key changes nothing and reports the rows as unreadable.
- **Lost key, no copy** **(emergency SQL)**: nothing encrypted can be read. Start with a new key; then, with the service stopped:
  ```sql
  SET search_path = iam;
  DELETE FROM signing_key;                                   -- a new key is created at start; every token issued so far stops verifying
  UPDATE app_user SET mfa_enabled = FALSE, mfa_secret_enc = NULL, mfa_pending_enc = NULL, mfa_last_step = NULL;
  DELETE FROM mfa_recovery_code;                             -- everyone enrols the second factor again at next sign-in
  DELETE FROM webhook_delivery; DELETE FROM webhook_subscription WHERE approval_type_code IS NULL;
  UPDATE webhook_subscription SET active = FALSE;            -- approval-type callbacks: re-save each type with its callback URL to get a new secret
  DELETE FROM oauth2_authorization;
  ```
  Start the service, and recreate webhooks (consumers need the new secrets). Passwords and the audit trail are unaffected.

## 7. Nobody can administer

Locks clear themselves after 15 minutes, expired passwords can be changed at sign-in, so most "all administrators locked out" cases fix themselves. If every user who holds `IAM_USER_ADMIN` is suspended or offboarded:

1. Set `IAM_BOOTSTRAP_USERNAME` (the name of the original bootstrap account, or a free name) and `IAM_BOOTSTRAP_PASSWORD` and restart.
2. The service sees that nobody who administers users could sign in, reactivates the bootstrap account with that password, clears its second factor (it enrols again at the next sign-in), gives it the three identity roles and writes `BOOTSTRAP_RECOVERY` to the audit trail. If at least one administrator can sign in, the settings do nothing.
3. Sign in, repair the named accounts (`reinstate`, `reset-password`, `reset-mfa`), suspend the bootstrap account, remove the settings.

Holding the service's environment is the trust this rests on: whoever can set it can also read the encryption key.

## 8. Customers

- **Create** (`POST /api/v1/users`, `kind: CUSTOMER`, with the `customerId` as known to lending). The customer gets the `CUSTOMER_SELF` role and a one-time **activation token** (72 h). IAM sends nothing: the portal back end or the branch delivers it. The customer posts token, new password and the accepted consents to `POST /api/v1/public/activate`; every mandatory consent must be accepted.
- **Links** (what the customer may see): `POST /api/v1/customers/{userId}/links`. An `OWNER` link on the customer's own number is active at once; co-borrower, guarantor and delegate links on one account wait for a **different** administrator holding `iam.customer.link.approve`. A delegate link needs an end date and stops by itself. Revoking works at the next check. Without a link a customer's account-bound permissions are refused (`ACCOUNT_NOT_LINKED`).
- **Consents** are versioned. Raising the version of a mandatory purpose (`UPDATE consent_purpose SET version = version + 1 ...`, or a migration) makes every customer accept it at the next sign-in. The history table cannot be changed or deleted. A mandatory consent cannot be withdrawn while the customer is active; offboarding is the way out.
- **Expired activation token**: `reset-password` on the pending customer issues a new token and kills the old one.

## 9. Webhooks and events

Every state change writes an event in the same transaction; a background sweep (every minute, `IAM_SWEEP_CRON`) fans events out to subscriptions and delivers them signed (`X-IAM-Signature: sha256=<hex HMAC-SHA256 of the body>`). Events of one subject (a user, an approval) arrive in order: while one is retrying, later ones for the same subject wait. A delivery is retried with back-off (30 s, 1 min, 2 min ... up to 1 h) and is `FAILED` after 8 attempts; later events of that subject then keep waiting until you fix the receiver and replay it (`POST /api/v1/webhooks/deliveries/{id}/replay`). Watch the count of `FAILED` deliveries (`GET /api/v1/webhooks/deliveries?status=FAILED`). Only `https` addresses are accepted, never loopback, private, link-local or metadata addresses.

## 10. Audit trail

`GET /api/v1/audit/verify` recomputes the hash chain (all rows, or a time range) and reports the first broken row. Run it daily and alert when `ok` is false. The database also refuses `UPDATE`, `DELETE` and `TRUNCATE` on the table; someone with administrative rights on the database could disable that, and the chain is what would then show the change. Reading and verifying the trail are themselves audited. Default retention is "keep everything"; the specification proposes 8 years, to be confirmed by Compliance. Restoring an older backup removes the audit rows written after it; the chain of what remains still verifies.

## 11. Adopting IAM in a service

Nothing consumes IAM yet. For each service, in this order (specification section 10):

1. Create its service account and give it the matching `SVC_*` role.
2. Add the resource-server dependency and set `issuer-uri` to `IAM_ISSUER`. Accept only tokens whose `typ` claim is `STAFF`, `CUSTOMER` or `SERVICE` as appropriate (an ID token has none and must be refused). Map `perms` to authorities and guard each endpoint with the permission code that the specification names for it.
3. Keep the old Basic credential next to bearer tokens (`both`) and compare in the logs and the audit trail what each accepts; then switch to `tokens` only.
4. Where a decision needs an amount, branch or account, call `POST /api/v1/authz/check` as the service account (`iam.authz.check`), naming the subject.

lending-service has no authentication today: switching it on is a behaviour change for every caller (batch, admin, LCM), so do it per caller with the credential of that caller.

## 12. Changing the catalogue

Permissions, roles, separation-of-duties rules and approval types are data in `tools/catalogue.js`. For a deployed system do not edit V2 or V3: write a new migration (V6, ...) with the `INSERT`/`UPDATE` statements. A new permission is invisible to users until a role gets it (`PUT /api/v1/roles/{code}/permissions` for custom roles; system roles change by migration). Review the seeded roles and rules with the business owners and Internal Audit before go-live; they are placeholders for policy.

## 13. Monitoring

`/actuator/prometheus`: `iam_signin_total{result}`, `iam_authz_decisions_total{code}`, `iam_approvals_total{status}`. Alert on a burst of `result="failure"` or `"locked"`, on any `AMOUNT_ABOVE_LIMIT` or `NOT_GRANTED` spike from a service account (a mis-mapped permission after a release), on `FAILED` webhook deliveries, on `/audit/verify` returning `ok: false`, and on the sweep not running (pending approvals past their expiry).

## 14. Known limits

- One instance. Sessions of the sign-in pages are held in memory, so two instances need shared sessions (phase E). Tokens do not depend on the instance.
- The bean wiring, the three security filter chains, the integration with Spring Authorization Server (client repository, JDBC authorization table layout, token customizer) and the sign-in pages are desk-checked only. First deployment is the first real test; follow section 1 and then sign in end to end before creating anyone.
- The permission codes are checked against what LCM names (all 29 exist); the codes for lending, batch, admin and accounting endpoints come from the specification's inventory of those services.
