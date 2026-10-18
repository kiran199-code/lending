-- V1: facilities (limits), maker-checker changes, temporary excess, reservations, positions,
-- append-only limit ledger, idempotency, event feed and audit. Runs in schema 'lcm' (Flyway default schema).

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE facility (
    id                BIGSERIAL PRIMARY KEY,
    facility_ref      VARCHAR(40) NOT NULL UNIQUE,
    customer_id       BIGINT NOT NULL,
    parent_id         BIGINT REFERENCES facility(id),
    facility_type     VARCHAR(10) NOT NULL CHECK (facility_type IN ('OVERALL','SUBLIMIT','PRODUCT')),
    product_code      VARCHAR(10),
    currency_code     CHAR(3) NOT NULL,
    branch_code       VARCHAR(10),
    revolving         BOOLEAN NOT NULL DEFAULT FALSE,
    dp_governed       BOOLEAN NOT NULL DEFAULT FALSE,
    sanctioned_amount NUMERIC(19,2) NOT NULL CHECK (sanctioned_amount >= 0),
    max_single_amount NUMERIC(19,2) CHECK (max_single_amount IS NULL OR max_single_amount > 0),
    status            VARCHAR(10) NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','ACTIVE','SUSPENDED','FROZEN','EXPIRED','CLOSED')),
    effective_from    DATE,
    expiry_date       DATE,
    sanction_ref_id   BIGINT,                      -- lending sanction this facility was derived from (bridge)
    source            VARCHAR(10) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','BRIDGE')),
    -- Running counters. Every change is also written to limit_txn, so each counter equals the sum of its deltas.
    utilised          NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (utilised >= 0),     -- outstanding principal
    drawn_total       NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (drawn_total >= 0),  -- cumulative drawn (non-revolving consumption)
    reserved          NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    created_by        VARCHAR(60) NOT NULL,
    approved_by       VARCHAR(60),
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (expiry_date IS NULL OR effective_from IS NULL OR expiry_date >= effective_from),
    CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE UNIQUE INDEX uq_facility_sanction ON facility(sanction_ref_id) WHERE sanction_ref_id IS NOT NULL;
CREATE INDEX idx_facility_customer ON facility(customer_id);
CREATE INDEX idx_facility_parent ON facility(parent_id);
CREATE INDEX idx_facility_status ON facility(status);
CREATE TRIGGER trg_facility_updated_at BEFORE UPDATE ON facility FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Maker-checker for sensitive facility changes. payload carries the proposed values.
CREATE TABLE facility_change (
    id            BIGSERIAL PRIMARY KEY,
    facility_id   BIGINT NOT NULL REFERENCES facility(id),
    change_type   VARCHAR(20) NOT NULL CHECK (change_type IN
                    ('ACTIVATE','AMEND_LIMIT','EXTEND_EXPIRY','SUSPEND','REINSTATE','FREEZE','CLOSE')),
    payload       TEXT,
    reason        VARCHAR(400),
    status        VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    maker         VARCHAR(60) NOT NULL,
    checker       VARCHAR(60),
    decision_note VARCHAR(400),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at    TIMESTAMPTZ
);
CREATE INDEX idx_facility_change_status ON facility_change(status, created_at);
CREATE INDEX idx_facility_change_facility ON facility_change(facility_id);

-- Temporary excess over the sanctioned limit, valid for a date range.
CREATE TABLE facility_excess (
    id            BIGSERIAL PRIMARY KEY,
    facility_id   BIGINT NOT NULL REFERENCES facility(id),
    amount        NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    valid_from    DATE NOT NULL,
    valid_to      DATE NOT NULL,
    reason        VARCHAR(400) NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACTIVE','REJECTED')),
    maker         VARCHAR(60) NOT NULL,
    checker       VARCHAR(60),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at    TIMESTAMPTZ,
    CHECK (valid_to >= valid_from)
);
CREATE INDEX idx_facility_excess_facility ON facility_excess(facility_id, status);

-- Per drawdown (loan account) position against a facility.
CREATE TABLE utilisation_position (
    id             BIGSERIAL PRIMARY KEY,
    facility_id    BIGINT NOT NULL REFERENCES facility(id),
    reference_type VARCHAR(20) NOT NULL,
    reference_id   VARCHAR(60) NOT NULL,
    drawn          NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (drawn >= 0),
    repaid         NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (repaid >= 0),
    written_off    NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (written_off >= 0),
    status         VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    version        BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (reference_type, reference_id),
    CHECK (repaid + written_off <= drawn)
);
CREATE INDEX idx_position_facility ON utilisation_position(facility_id);
CREATE TRIGGER trg_position_updated_at BEFORE UPDATE ON utilisation_position FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE reservation (
    id              BIGSERIAL PRIMARY KEY,
    reservation_ref VARCHAR(40) NOT NULL UNIQUE DEFAULT gen_random_uuid()::text,
    facility_id     BIGINT NOT NULL REFERENCES facility(id),
    amount          NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    confirmed_amount NUMERIC(19,2) CHECK (confirmed_amount IS NULL OR confirmed_amount >= 0),
    currency_code   CHAR(3) NOT NULL,
    reference_type  VARCHAR(20) NOT NULL,
    reference_id    VARCHAR(60) NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'RESERVED'
                      CHECK (status IN ('RESERVED','CONFIRMED','RELEASED','EXPIRED')),
    enforced        BOOLEAN NOT NULL DEFAULT TRUE,
    would_reject    VARCHAR(80),                 -- shadow mode: the rule that would have rejected this
    late_confirm    BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_by      VARCHAR(60) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reservation_open ON reservation(expires_at) WHERE status = 'RESERVED';
CREATE INDEX idx_reservation_ref ON reservation(reference_type, reference_id);
-- At most one live reservation per business reference (e.g. one pending disbursement per loan account).
CREATE UNIQUE INDEX uq_reservation_live ON reservation(reference_type, reference_id) WHERE status = 'RESERVED';
CREATE INDEX idx_reservation_facility ON reservation(facility_id);
CREATE TRIGGER trg_reservation_updated_at BEFORE UPDATE ON reservation FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Append-only ledger: one row per facility level touched by an operation (leaf and each ancestor).
CREATE TABLE limit_txn (
    id                 BIGSERIAL PRIMARY KEY,
    facility_id        BIGINT NOT NULL REFERENCES facility(id),
    origin_facility_id BIGINT NOT NULL REFERENCES facility(id),
    txn_type           VARCHAR(12) NOT NULL CHECK (txn_type IN
                         ('RESERVE','CONFIRM','RELEASE','EXPIRE','REPAY','CLOSE','OPENING')),
    reservation_id     BIGINT REFERENCES reservation(id),
    position_id        BIGINT REFERENCES utilisation_position(id),
    amount             NUMERIC(19,2) NOT NULL,
    d_reserved         NUMERIC(19,2) NOT NULL DEFAULT 0,
    d_utilised         NUMERIC(19,2) NOT NULL DEFAULT 0,
    d_drawn_total      NUMERIC(19,2) NOT NULL DEFAULT 0,
    reference_type     VARCHAR(20),
    reference_id       VARCHAR(60),
    actor              VARCHAR(60) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_limit_txn_facility ON limit_txn(facility_id, id);
CREATE INDEX idx_limit_txn_ref ON limit_txn(reference_type, reference_id);

CREATE OR REPLACE FUNCTION limit_txn_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'limit_txn is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_limit_txn_no_update BEFORE UPDATE OR DELETE ON limit_txn
    FOR EACH ROW EXECUTE FUNCTION limit_txn_immutable();

-- Idempotency: key + hash of the request. result_ref points at the created/affected record.
CREATE TABLE idempotency_key (
    idem_key     VARCHAR(100) PRIMARY KEY,
    operation    VARCHAR(20) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    result_ref   VARCHAR(60),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Event feed (pull). tx_id lets consumers read only below the oldest in-flight transaction,
-- so a consumer never skips an event that commits later than a higher-numbered one.
CREATE TABLE lcm_event (
    id             BIGSERIAL PRIMARY KEY,
    tx_id          BIGINT NOT NULL DEFAULT (pg_current_xact_id()::text::bigint),
    event_type     VARCHAR(40) NOT NULL,
    aggregate_type VARCHAR(20) NOT NULL,
    aggregate_ref  VARCHAR(60) NOT NULL,
    payload        TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_lcm_event_order ON lcm_event(tx_id, id);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(20) NOT NULL,
    entity_ref  VARCHAR(60) NOT NULL,
    action      VARCHAR(40) NOT NULL,
    actor       VARCHAR(60) NOT NULL,
    detail      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_ref);
