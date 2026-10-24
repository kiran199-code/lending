# Accounting engine: Phase A build notes

Companion to *Accounting Entries Requirements v0.1*. It records what Phase A builds, where the build differs from the
document (so the document can be brought to v0.2), and what is left for Phases B to F.

## Phase map

| Phase | Scope | State |
|---|---|---|
| A | Core model, engine, internal ledger, hooks for disbursement, accrual, demand, advance, penal, EMI, part-payment and foreclosure; read APIs; reconciliation; opening-balance job; batch jobs; admin pages | Built |
| B | NPA suspense (interest not recognised as income once an account is non-performing) | Not started |
| C | Provisioning, write-off, recovery | Not started |
| D | Waiver, payment reversal, manual journals | Not started |
| E | Multi-currency, FX rates, revaluation (the currency/branch place-holders and the per-account currency are built in Phase A; mixed-currency journals are refused until E) | Not started |
| F | Delivery batches and adapters to an external ledger, external reconciliation, period close | Not started |

## Differences from requirements v0.1 (for the v0.2 update)

1. **Reversal link table.** Journals are append-only, so "reversed by" cannot be written onto the original. The link lives in
   `acct_journal_reversal(original_journal_id, reversal_journal_id)`; the reversal journal also carries `reversal_of_journal_id`. Nothing writes to the table until Phase D.
2. **Extra amount tags** beyond the document's list, needed by the true-up and the cutover: `INT_TRUEUP_AMT`, `INT_TRUEDOWN_AMT`,
   `OPENING_*` tags per bucket.
3. **`INT_TRUEUP` event.** Interest receivable is trued to the interest actually demanded when a demand is raised and at foreclosure
   (the document has the accrual and the demand as separate postings and does not say how rounding differences are cleared).
4. **Suspended accrual is one conditional template.** `INT_ACCRUE` has a single template whose rules are conditional on `npa_stage`, instead of a
   second template per stage. Phase B fills in the suspended branch; the context already carries `npa_stage` (the account status).
5. **`OPENING_BALANCE` is in Phase A,** not later, because reconciliation is meaningless for accounts that pre-date the journal.
6. **`posting_mode` is stored on each journal line,** not only on the header, so balances can exclude SHADOW journals with a single-table filter.
7. **`acct_config_audit`** records every change to events, templates, rules, routes, ledgers and accounts (who, when, before and after).
8. **Reconciliation date rule.** The sub-ledger is read as it stands now, so reconciliation runs for the current business date only; an earlier date is refused.
9. **Setting `accounting.recon-interest-tolerance`** (default 0.01) for accrued interest only; every other bucket is exact.
10. **Maker-checker** for templates and routes uses the `X-Actor` header; the approver must differ from the author.
11. **Currency and branch (V18), a requirement added after v0.1.** `currency` and `branch` reference tables (seeded INR and ONLINE); `loan_account.currency_code` and `branch_code` are required, chosen at creation and never changed; the poster takes both from the account for every journal. Journal header and lines store `branch_code`; `acct_route.branch_code` is optional (branch 8 > product 4 > stage 2 > currency 1); `acct_balance_daily` is keyed by branch as well. Reconciliation has a ninth check, `RECON_ACCOUNT_DIMENSIONS`, that fails if a journal's currency or branch differs from its account's. Only the base currency can be active until `accounting.multi-currency-enabled=true`, and the engine still refuses mixed-currency journals until Phase E.

## Known limitation to resolve

An interest accrual that fails (queued as a posting exception) and is retried **after** a later demand has already trued up the same interest
is counted twice in the journal. The reconciliation shows the account as differing in RECON_INTEREST_ACCRUED, which is the intended safety net.
Options for a later phase: make the true-up read the journal's posted-through date, or make a retry of an accrual older than the last demand post
a true-down instead.

## Operating notes

- Order of an upgrade: lending-service (V16, V17, V18) -> `POST /api/v1/accounting/opening-balance` -> batch service (V7) -> admin service.
- Turning accounting off (`accounting.enabled=false`) stops all posting and makes the reconciliation job pass with nothing compared. Turning it back on
  later leaves a gap for the period it was off; run the opening-balance job only for accounts that have no journal at all.
- A new event can be put in SHADOW before LIVE: journals are written and visible but excluded from balances and from reconciliation.
- Journals cannot be edited or deleted; the only purge is the test-data teardown, which opts in per transaction with
  `SELECT set_config('acct.allow_purge','on',true)`.
- To add a branch: `POST /api/v1/reference/branches` (`branchCode`, `branchName`). Its journals use the existing routes (a route without a branch serves all branches); add a route with `branch_code` only if that branch needs different GL accounts. New accounts can then be opened at it. Existing accounts stay where they are.
- Settings: `accounting.base-currency` (INR), `lending.default-branch` (ONLINE), `accounting.multi-currency-enabled` (false).
