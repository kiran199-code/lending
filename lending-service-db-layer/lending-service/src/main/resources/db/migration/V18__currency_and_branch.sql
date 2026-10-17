-- V18: currency and branch as reference data, linked to the loan account, and carried onto every accounting entry.
--
-- Today there is one currency (INR) and one branch (ONLINE). Both are rows in a reference table, so adding a
-- currency or a branch later is an insert (POST /api/v1/reference/currencies or /branches), not a release.
--
--  * loan_account.currency_code / branch_code are set when the account is created and never change. Existing
--    accounts get INR and ONLINE.
--  * Every journal and journal line records the branch of the account it was posted for; the journal's transaction
--    currency is the account's currency. Existing journals get ONLINE.
--  * A GL route may name a branch (empty = every branch), so a branch can later post to its own GL accounts.
--  * Daily balances are kept per ledger, GL account, currency and branch.
--
-- Account columns are VARCHAR (not CHAR) because the entity is validated against the schema by Hibernate.

CREATE TABLE currency (
    currency_code   VARCHAR(3)   PRIMARY KEY CHECK (currency_code ~ '^[A-Z]{3}$'),
    currency_name   VARCHAR(60)  NOT NULL,
    minor_units     SMALLINT     NOT NULL DEFAULT 2 CHECK (minor_units BETWEEN 0 AND 4),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE branch (
    branch_code     VARCHAR(10)  PRIMARY KEY CHECK (branch_code ~ '^[A-Z0-9_]{1,10}$'),
    branch_name     VARCHAR(100) NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO currency(currency_code, currency_name, minor_units, active) VALUES ('INR', 'Indian Rupee', 2, TRUE);
INSERT INTO branch(branch_code, branch_name, active) VALUES ('ONLINE', 'Online', TRUE);

ALTER TABLE loan_account
    ADD COLUMN currency_code VARCHAR(3)  NOT NULL DEFAULT 'INR'    REFERENCES currency(currency_code),
    ADD COLUMN branch_code   VARCHAR(10) NOT NULL DEFAULT 'ONLINE' REFERENCES branch(branch_code);
CREATE INDEX idx_loan_account_branch ON loan_account(branch_code);

ALTER TABLE acct_journal
    ADD COLUMN branch_code VARCHAR(10) NOT NULL DEFAULT 'ONLINE' REFERENCES branch(branch_code);
ALTER TABLE acct_journal_line
    ADD COLUMN branch_code VARCHAR(10) NOT NULL DEFAULT 'ONLINE' REFERENCES branch(branch_code);
CREATE INDEX idx_acct_line_branch ON acct_journal_line(branch_code, business_date);

ALTER TABLE acct_route
    ADD COLUMN branch_code VARCHAR(10) REFERENCES branch(branch_code);

ALTER TABLE acct_balance_daily
    ADD COLUMN branch_code VARCHAR(10) NOT NULL DEFAULT 'ONLINE' REFERENCES branch(branch_code);
ALTER TABLE acct_balance_daily DROP CONSTRAINT acct_balance_daily_pkey;
ALTER TABLE acct_balance_daily ADD PRIMARY KEY (ledger_id, gl_account_code, currency, branch_code, business_date);
