-- V2: the double-entry journal, plus the support tables every service needs (idempotency, event feed, audit).
-- Runs in schema 'chit' (Flyway default schema), so names are unqualified.
--
-- Journal rules enforced here, not only in Java:
--   * a journal has at least two lines, every line is a positive amount on one side, and debits equal credits;
--   * journals and lines are append-only (a correction is a reversing journal, never an UPDATE or DELETE);
--   * one posting_key per journal, so a retried operation can never post twice.
-- The only way to delete journal rows is the reset in the maintenance screen, which uses TRUNCATE (no row trigger fires)
-- or, for a targeted purge, the transaction-local setting chit.allow_purge = 'on'.

-- ---------------------------------------------------------------------------------------------------------------------
-- Idempotency: key + hash of the request. result_ref points at the created or affected record.
CREATE TABLE idempotency_key (
    idem_key     VARCHAR(100) PRIMARY KEY,
    operation    VARCHAR(30)  NOT NULL,
    request_hash CHAR(64)     NOT NULL,
    result_ref   VARCHAR(60),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------------------------------------------------
-- Event feed (pull). tx_id lets consumers read only below the oldest in-flight transaction, so a consumer never
-- skips an event that commits later than a higher-numbered one.
CREATE TABLE chit_event (
    id             BIGSERIAL PRIMARY KEY,
    tx_id          BIGINT       NOT NULL DEFAULT (pg_current_xact_id()::text::bigint),
    event_type     VARCHAR(40)  NOT NULL,
    aggregate_type VARCHAR(20)  NOT NULL,
    aggregate_ref  VARCHAR(60)  NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_chit_event_order ON chit_event(tx_id, id);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(20)  NOT NULL,
    entity_ref  VARCHAR(60)  NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    actor       VARCHAR(60)  NOT NULL,
    detail      TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_ref);

-- ---------------------------------------------------------------------------------------------------------------------
-- Ledger accounts. One set per group; the per-ticket accounts carry ticket_id. The account type list is the chart of
-- accounts from section 12 of the prompt plus the four accounts the reviewed templates need.
-- normal_side says which side increases the balance (assets and expenses DEBIT, liabilities and income CREDIT).
CREATE TABLE ledger_account (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT       NOT NULL REFERENCES chit_group(id),
    ticket_id   BIGINT       REFERENCES chit_ticket(id),
    account_type VARCHAR(32) NOT NULL CHECK (account_type IN (
                    'POOL_CASH','SECURITY_CASH','FOREMAN_CASH','CHIT_POOL',
                    'SUBSCRIBER_RECEIVABLE','PRIZE_PAYABLE','DIVIDEND_PAYABLE',
                    'COMMISSION_INCOME','LATE_FEE_INCOME','ENROLMENT_FEE_INCOME','TRANSFER_FEE_INCOME',
                    'SECURITY_DEPOSIT_LIABILITY','FOREMAN_GUARANTEE_RECEIVABLE','FOREMAN_FLOAT_PAYABLE',
                    'UNCLAIMED','WRITE_OFF_LOSS','GUARANTEE_LOSS_EXPENSE')),
    normal_side CHAR(1)      NOT NULL CHECK (normal_side IN ('D','C')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- per-ticket accounts must name a ticket; group-level accounts must not
    CONSTRAINT ck_ledger_account_ticket CHECK (
        (account_type IN ('SUBSCRIBER_RECEIVABLE','DIVIDEND_PAYABLE','SECURITY_DEPOSIT_LIABILITY','FOREMAN_GUARANTEE_RECEIVABLE'))
        = (ticket_id IS NOT NULL))
);
-- NULL ticket_id never collides in a plain UNIQUE, so the group-level accounts get their own partial index.
CREATE UNIQUE INDEX uq_ledger_account_group ON ledger_account(group_id, account_type) WHERE ticket_id IS NULL;
CREATE UNIQUE INDEX uq_ledger_account_ticket ON ledger_account(group_id, account_type, ticket_id) WHERE ticket_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------------------
-- Journals and lines.
CREATE TABLE ledger_journal (
    id             BIGSERIAL PRIMARY KEY,
    group_id       BIGINT       NOT NULL REFERENCES chit_group(id),
    template       VARCHAR(40)  NOT NULL,
    posting_key    VARCHAR(120) NOT NULL UNIQUE,
    cycle_id       BIGINT       REFERENCES chit_cycle(id),
    business_date  DATE         NOT NULL,
    reference_type VARCHAR(20),
    reference_id   VARCHAR(60),
    narration      VARCHAR(200),
    reverses_journal_id BIGINT  UNIQUE REFERENCES ledger_journal(id),
    created_by     VARCHAR(60)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_ledger_journal_group ON ledger_journal(group_id, id);
CREATE INDEX idx_ledger_journal_cycle ON ledger_journal(cycle_id);
CREATE INDEX idx_ledger_journal_ref ON ledger_journal(reference_type, reference_id);

CREATE TABLE ledger_line (
    id          BIGSERIAL PRIMARY KEY,
    journal_id  BIGINT        NOT NULL REFERENCES ledger_journal(id),
    line_no     INTEGER       NOT NULL CHECK (line_no >= 1),
    account_id  BIGINT        NOT NULL REFERENCES ledger_account(id),
    side        CHAR(1)       NOT NULL CHECK (side IN ('D','C')),
    amount      NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    UNIQUE (journal_id, line_no)
);
CREATE INDEX idx_ledger_line_account ON ledger_line(account_id, id);

-- Append-only: no UPDATE ever; DELETE only inside a purge that has switched chit.allow_purge on for its own transaction.
CREATE OR REPLACE FUNCTION ledger_immutable() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' AND COALESCE(current_setting('chit.allow_purge', true), 'off') = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION '% is append-only; post a reversing journal instead', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_ledger_journal_immutable BEFORE UPDATE OR DELETE ON ledger_journal
    FOR EACH ROW EXECUTE FUNCTION ledger_immutable();
CREATE TRIGGER trg_ledger_line_immutable BEFORE UPDATE OR DELETE ON ledger_line
    FOR EACH ROW EXECUTE FUNCTION ledger_immutable();

-- Balance check, deferred to commit so a journal can be inserted line by line. Fires once per inserted line, which is
-- harmless: the check reads the whole journal each time and passes or fails identically.
CREATE OR REPLACE FUNCTION ledger_journal_balanced() RETURNS TRIGGER AS $$
DECLARE
    debits  NUMERIC(18,2);
    credits NUMERIC(18,2);
    n       INTEGER;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE side = 'D'), 0),
           COALESCE(SUM(amount) FILTER (WHERE side = 'C'), 0),
           COUNT(*)
      INTO debits, credits, n
      FROM ledger_line WHERE journal_id = NEW.journal_id;
    IF n < 2 OR debits <> credits THEN
        RAISE EXCEPTION 'Journal % is not balanced: debits %, credits %, % lines', NEW.journal_id, debits, credits, n
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER trg_ledger_journal_balanced AFTER INSERT ON ledger_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_journal_balanced();

-- A line must sit on an account of the same group as its journal.
CREATE OR REPLACE FUNCTION ledger_line_same_group() RETURNS TRIGGER AS $$
DECLARE
    jg BIGINT;
    ag BIGINT;
BEGIN
    SELECT group_id INTO jg FROM ledger_journal WHERE id = NEW.journal_id;
    SELECT group_id INTO ag FROM ledger_account WHERE id = NEW.account_id;
    IF jg IS DISTINCT FROM ag THEN
        RAISE EXCEPTION 'Journal % (group %) cannot post to account % (group %)', NEW.journal_id, jg, NEW.account_id, ag
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_ledger_line_same_group BEFORE INSERT ON ledger_line
    FOR EACH ROW EXECUTE FUNCTION ledger_line_same_group();
