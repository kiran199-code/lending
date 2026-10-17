-- V16: Accounting Entries module, core schema (requirements REQ-AC-001 .. ; phase A).
--
-- Configuration (events, amount tags, roles, templates, rules, ledgers, accounts, routes), the append-only journal
-- (partitioned monthly by business_date, like emi_transaction), and the supporting tables for idempotency, accrual
-- rounding carry, posting exceptions, daily balances and accounting periods. Seed data is in V17.
--
-- Deviation from the requirements text, recorded here: "journals are append-only" and "the original header holds
-- reversed_by" cannot both hold, so the reversal link lives in acct_journal_reversal and the header is never updated.

-- ------------------------------------------------------------------------------------------------ configuration

CREATE TABLE acct_event_def (
    event_code           VARCHAR(40)  PRIMARY KEY,
    name                 VARCHAR(120) NOT NULL,
    category             VARCHAR(12)  NOT NULL
                            CHECK (category IN ('CORE','FEE','NPA','PROVISION','WRITEOFF','FX','MANUAL','SYSTEM')),
    source_module        VARCHAR(40)  NOT NULL,
    description          TEXT,
    blocking_mode        VARCHAR(12)  NOT NULL DEFAULT 'BLOCKING' CHECK (blocking_mode IN ('BLOCKING','NON_BLOCKING')),
    posting_mode         VARCHAR(8)   NOT NULL DEFAULT 'LIVE' CHECK (posting_mode IN ('OFF','SHADOW','LIVE')),
    eod_gate             BOOLEAN      NOT NULL DEFAULT FALSE,
    on_missing_template  VARCHAR(24)  NOT NULL DEFAULT 'FAIL' CHECK (on_missing_template IN ('FAIL','SKIP_WITH_EXCEPTION')),
    narrative_pattern    VARCHAR(200),
    fx_rate_type         VARCHAR(20)  NOT NULL DEFAULT 'SPOT_MID',
    status               VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE acct_amount_tag (
    event_code           VARCHAR(40)  NOT NULL REFERENCES acct_event_def(event_code),
    tag_code             VARCHAR(40)  NOT NULL,
    description          VARCHAR(200),
    PRIMARY KEY (event_code, tag_code)
);

CREATE TABLE acct_role (
    role_code            VARCHAR(40)  PRIMARY KEY,
    name                 VARCHAR(120) NOT NULL,
    nature               VARCHAR(10)  NOT NULL CHECK (nature IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE','MEMO')),
    normal_balance       CHAR(2)      NOT NULL CHECK (normal_balance IN ('DR','CR'))
);

CREATE TABLE gl_ledger (
    ledger_id            INT          PRIMARY KEY,
    ledger_code          VARCHAR(30)  NOT NULL UNIQUE,
    name                 VARCHAR(120) NOT NULL,
    ledger_type          VARCHAR(10)  NOT NULL CHECK (ledger_type IN ('INTERNAL','EXTERNAL','MEMO')),
    purpose              VARCHAR(12)  NOT NULL CHECK (purpose IN ('PRIMARY','MANAGEMENT','REGULATORY','MEMO')),
    base_currency        CHAR(3)      NOT NULL,
    adapter_code         VARCHAR(40),
    delivery_mode        VARCHAR(10)  NOT NULL DEFAULT 'IMMEDIATE' CHECK (delivery_mode IN ('IMMEDIATE','EOD_BATCH')),
    granularity          VARCHAR(30)  NOT NULL DEFAULT 'PER_LINE'
                            CHECK (granularity IN ('PER_LINE','SUMMARY_ACCOUNT_DAY','SUMMARY_ACCOUNT_PRODUCT_DAY')),
    npa_treatment        VARCHAR(10)  NOT NULL DEFAULT 'SUSPENSE' CHECK (npa_treatment IN ('SUSPENSE','MEMO')),
    upgrade_policy       VARCHAR(24)  NOT NULL DEFAULT 'RELEASE_ON_UPGRADE'
                            CHECK (upgrade_policy IN ('RELEASE_ON_UPGRADE','RELEASE_ON_REALISATION')),
    status               VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
    effective_from       DATE         NOT NULL DEFAULT DATE '2000-01-01',
    effective_to         DATE,
    CHECK (ledger_type <> 'EXTERNAL' OR adapter_code IS NOT NULL)
);

CREATE TABLE gl_account (
    ledger_id            INT          NOT NULL REFERENCES gl_ledger(ledger_id),
    gl_account_code      VARCHAR(30)  NOT NULL,
    name                 VARCHAR(120) NOT NULL,
    nature               VARCHAR(10)  NOT NULL CHECK (nature IN ('ASSET','LIABILITY','EQUITY','INCOME','EXPENSE','MEMO')),
    normal_balance       CHAR(2)      NOT NULL CHECK (normal_balance IN ('DR','CR')),
    parent_code          VARCHAR(30),
    currency_restriction VARCHAR(100),
    posting_allowed      BOOLEAN      NOT NULL DEFAULT TRUE,
    manual_allowed       BOOLEAN      NOT NULL DEFAULT FALSE,
    status               VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','BLOCKED','CLOSED')),
    PRIMARY KEY (ledger_id, gl_account_code)
);

CREATE TABLE acct_route (
    route_id             BIGSERIAL    PRIMARY KEY,
    ledger_id            INT          NOT NULL REFERENCES gl_ledger(ledger_id),
    role_code            VARCHAR(40)  NOT NULL REFERENCES acct_role(role_code),
    product_code         VARCHAR(30),
    npa_stage            VARCHAR(20),
    currency             CHAR(3),
    gl_account_code      VARCHAR(30)  NOT NULL,
    priority             INT          NOT NULL DEFAULT 0,
    effective_from       DATE         NOT NULL DEFAULT DATE '2000-01-01',
    effective_to         DATE,
    status               VARCHAR(10)  NOT NULL DEFAULT 'APPROVED' CHECK (status IN ('DRAFT','APPROVED','RETIRED')),
    created_by           VARCHAR(60),
    approved_by          VARCHAR(60),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    FOREIGN KEY (ledger_id, gl_account_code) REFERENCES gl_account(ledger_id, gl_account_code)
);
CREATE INDEX idx_acct_route_lookup ON acct_route(ledger_id, role_code) WHERE status = 'APPROVED';

CREATE TABLE acct_event_ledger (
    event_code           VARCHAR(40)  NOT NULL REFERENCES acct_event_def(event_code),
    ledger_id            INT          NOT NULL REFERENCES gl_ledger(ledger_id),
    PRIMARY KEY (event_code, ledger_id)
);

CREATE TABLE acct_entry_template (
    template_id          BIGSERIAL    PRIMARY KEY,
    event_code           VARCHAR(40)  NOT NULL REFERENCES acct_event_def(event_code),
    product_code         VARCHAR(30),
    version              INT          NOT NULL,
    status               VARCHAR(10)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','RETIRED')),
    effective_from       DATE         NOT NULL DEFAULT DATE '2000-01-01',
    effective_to         DATE,
    description          VARCHAR(300),
    created_by           VARCHAR(60),
    approved_by          VARCHAR(60),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    approved_at          TIMESTAMPTZ,
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CHECK (status <> 'APPROVED' OR (approved_by IS NOT NULL AND created_by IS DISTINCT FROM approved_by)
           OR created_by IS NULL)
);
CREATE UNIQUE INDEX uq_acct_template_version ON acct_entry_template(event_code, COALESCE(product_code, ''), version);

CREATE TABLE acct_entry_rule (
    rule_id              BIGSERIAL    PRIMARY KEY,
    template_id          BIGINT       NOT NULL REFERENCES acct_entry_template(template_id) ON DELETE CASCADE,
    line_seq             INT          NOT NULL,
    side                 CHAR(2)      NOT NULL CHECK (side IN ('DR','CR')),
    role_code            VARCHAR(40)  NOT NULL REFERENCES acct_role(role_code),
    amount_source        VARCHAR(400) NOT NULL,
    condition_expr       VARCHAR(400),
    ledger_scope         VARCHAR(40),
    currency_basis       VARCHAR(4)   NOT NULL DEFAULT 'TXN' CHECK (currency_basis IN ('TXN','BASE')),
    narrative            VARCHAR(200),
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (template_id, line_seq)
);

-- ------------------------------------------------------------------------------------------------ journal

CREATE SEQUENCE acct_journal_seq START 1;
CREATE SEQUENCE acct_line_seq START 1;

CREATE TABLE acct_journal (
    journal_id               BIGINT       NOT NULL,
    journal_no               VARCHAR(20)  NOT NULL,
    event_code               VARCHAR(40)  NOT NULL REFERENCES acct_event_def(event_code),
    template_id              BIGINT,
    template_version         INT,
    account_id               BIGINT,
    product_code             VARCHAR(30),
    source_type              VARCHAR(30)  NOT NULL,
    source_id                BIGINT,
    source_date              DATE,
    idempotency_key          VARCHAR(200) NOT NULL,
    business_date            DATE         NOT NULL,
    value_date               DATE         NOT NULL,
    txn_currency             CHAR(3)      NOT NULL,
    base_currency            CHAR(3)      NOT NULL,
    posting_mode             VARCHAR(8)   NOT NULL DEFAULT 'LIVE' CHECK (posting_mode IN ('SHADOW','LIVE')),
    is_backdated             BOOLEAN      NOT NULL DEFAULT FALSE,
    prior_period             BOOLEAN      NOT NULL DEFAULT FALSE,
    reversal_of_journal_id   BIGINT,
    narrative                VARCHAR(300),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by               VARCHAR(60),
    PRIMARY KEY (journal_id, business_date)
) PARTITION BY RANGE (business_date);

CREATE TABLE acct_journal_line (
    line_id                  BIGINT       NOT NULL,
    journal_id               BIGINT       NOT NULL,
    business_date            DATE         NOT NULL,
    line_seq                 INT          NOT NULL,
    ledger_id                INT          NOT NULL REFERENCES gl_ledger(ledger_id),
    gl_account_code          VARCHAR(30)  NOT NULL,
    role_code                VARCHAR(40)  NOT NULL,
    side                     CHAR(2)      NOT NULL CHECK (side IN ('DR','CR')),
    amount_txn               NUMERIC(19,4) CHECK (amount_txn IS NULL OR amount_txn > 0),
    currency_txn             CHAR(3),
    fx_rate                  NUMERIC(19,8),
    fx_rate_type             VARCHAR(20),
    amount_base              NUMERIC(19,4) NOT NULL CHECK (amount_base > 0),
    currency_base            CHAR(3)      NOT NULL,
    account_id               BIGINT,
    product_code             VARCHAR(30),
    npa_stage                VARCHAR(20),
    dimensions               JSONB,
    rule_id                  BIGINT,
    rate_stale               BOOLEAN      NOT NULL DEFAULT FALSE,
    narrative                VARCHAR(300),
    posting_mode             VARCHAR(8)   NOT NULL DEFAULT 'LIVE' CHECK (posting_mode IN ('SHADOW','LIVE')),
    PRIMARY KEY (line_id, business_date),
    FOREIGN KEY (journal_id, business_date) REFERENCES acct_journal(journal_id, business_date),
    FOREIGN KEY (ledger_id, gl_account_code) REFERENCES gl_account(ledger_id, gl_account_code)
) PARTITION BY RANGE (business_date);

CREATE INDEX idx_acct_journal_account ON acct_journal(account_id, business_date);
CREATE INDEX idx_acct_journal_event ON acct_journal(event_code, business_date);
CREATE INDEX idx_acct_journal_no ON acct_journal(journal_no);
CREATE INDEX idx_acct_line_journal ON acct_journal_line(journal_id, business_date);
CREATE INDEX idx_acct_line_account ON acct_journal_line(account_id, role_code);
CREATE INDEX idx_acct_line_gl ON acct_journal_line(ledger_id, gl_account_code, business_date);

DO $$
DECLARE
    m DATE := DATE '2026-07-01';
BEGIN
    WHILE m <= DATE '2027-12-01' LOOP
        PERFORM create_monthly_partition('acct_journal',      EXTRACT(YEAR FROM m)::INT, EXTRACT(MONTH FROM m)::INT);
        PERFORM create_monthly_partition('acct_journal_line', EXTRACT(YEAR FROM m)::INT, EXTRACT(MONTH FROM m)::INT);
        m := (m + INTERVAL '1 month')::DATE;
    END LOOP;
END $$;

-- Append-only: the application can insert but never update or delete a journal. A maintenance routine that must
-- purge (test data teardown) opts in per transaction with: SELECT set_config('acct.allow_purge', 'on', true).
CREATE OR REPLACE FUNCTION acct_block_mutation() RETURNS trigger AS $$
BEGIN
    IF current_setting('acct.allow_purge', true) = 'on' THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Journal tables are append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_acct_journal_immutable BEFORE UPDATE OR DELETE ON acct_journal
    FOR EACH ROW EXECUTE FUNCTION acct_block_mutation();
CREATE TRIGGER trg_acct_journal_line_immutable BEFORE UPDATE OR DELETE ON acct_journal_line
    FOR EACH ROW EXECUTE FUNCTION acct_block_mutation();

-- ------------------------------------------------------------------------------------------------ support tables

-- One row per posted idempotency key. A partitioned table cannot enforce uniqueness across partitions, so the
-- key lives here, where it can.
CREATE TABLE acct_idempotency (
    idempotency_key      VARCHAR(200) PRIMARY KEY,
    journal_id           BIGINT       NOT NULL,
    business_date        DATE         NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE acct_journal_reversal (
    original_journal_id  BIGINT       PRIMARY KEY,
    reversal_journal_id  BIGINT       NOT NULL UNIQUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Cumulative accrual per stream, so that journals post the change in the rounded cumulative (no rounding drift).
-- stream INT: ref_id 0 (per account); stream PENAL: ref_id = demand id (matches loan_demand.penal_accrued).
CREATE TABLE acct_accrual_carry (
    account_id           BIGINT       NOT NULL,
    stream               VARCHAR(20)  NOT NULL,
    ref_id               BIGINT       NOT NULL DEFAULT 0,
    cum_accrued          NUMERIC(19,4) NOT NULL DEFAULT 0,
    cum_posted           NUMERIC(19,4) NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, stream, ref_id)
);

CREATE TABLE acct_posting_exception (
    exception_id         BIGSERIAL    PRIMARY KEY,
    event_code           VARCHAR(40)  NOT NULL,
    account_id           BIGINT,
    idempotency_key      VARCHAR(200) NOT NULL,
    payload              JSONB        NOT NULL,
    reason               VARCHAR(30),
    error_message        VARCHAR(1000),
    eod_gate             BOOLEAN      NOT NULL DEFAULT FALSE,
    attempts             INT          NOT NULL DEFAULT 1,
    status               VARCHAR(10)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RETRIED','RESOLVED','WAIVED')),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_attempt_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_by          VARCHAR(60),
    resolved_note        VARCHAR(300)
);
CREATE INDEX idx_acct_exception_open ON acct_posting_exception(status, eod_gate) WHERE status = 'OPEN';
CREATE UNIQUE INDEX uq_acct_exception_open_key ON acct_posting_exception(idempotency_key) WHERE status = 'OPEN';

CREATE TABLE acct_balance_daily (
    ledger_id            INT          NOT NULL REFERENCES gl_ledger(ledger_id),
    gl_account_code      VARCHAR(30)  NOT NULL,
    currency             CHAR(3)      NOT NULL,
    business_date        DATE         NOT NULL,
    opening              NUMERIC(19,4) NOT NULL,
    debits               NUMERIC(19,4) NOT NULL,
    credits              NUMERIC(19,4) NOT NULL,
    closing              NUMERIC(19,4) NOT NULL,
    PRIMARY KEY (ledger_id, gl_account_code, currency, business_date)
);

CREATE TABLE acct_reconciliation (
    recon_id             BIGSERIAL    PRIMARY KEY,
    business_date        DATE         NOT NULL,
    check_code           VARCHAR(40)  NOT NULL,
    status               VARCHAR(6)   NOT NULL CHECK (status IN ('PASS','FAIL')),
    expected_amount      NUMERIC(19,4),
    actual_amount        NUMERIC(19,4),
    difference           NUMERIC(19,4),
    detail               VARCHAR(500),
    run_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_acct_recon_date ON acct_reconciliation(business_date, check_code);

CREATE TABLE acct_period (
    period_code          VARCHAR(7)   PRIMARY KEY,          -- YYYY-MM
    start_date           DATE         NOT NULL,
    end_date             DATE         NOT NULL,
    status               VARCHAR(12)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','SOFT_CLOSED','CLOSED')),
    closed_by            VARCHAR(60),
    closed_at            TIMESTAMPTZ
);

-- Who changed accounting configuration, and what. Written by the admin services, never updated.
CREATE TABLE acct_config_audit (
    audit_id             BIGSERIAL    PRIMARY KEY,
    entity               VARCHAR(20)  NOT NULL,
    entity_key           VARCHAR(80)  NOT NULL,
    action               VARCHAR(20)  NOT NULL,
    actor                VARCHAR(60)  NOT NULL,
    detail               VARCHAR(500),
    at                   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_acct_config_audit_entity ON acct_config_audit(entity, entity_key);
