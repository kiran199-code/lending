-- V2: Mortgage collateral (property only, this phase) + valuation history

CREATE TABLE mortgage (
    mortgage_id              BIGSERIAL PRIMARY KEY,
    account_id               BIGINT NOT NULL UNIQUE
                                REFERENCES loan_account(account_id),
    property_type            VARCHAR(15) NOT NULL
                                CHECK (property_type IN ('RESIDENTIAL','COMMERCIAL')),
    property_address         TEXT NOT NULL,
    valuation_amount         NUMERIC(15,2) NOT NULL CHECK (valuation_amount > 0),
    valuation_date           DATE NOT NULL,
    valuer_ref               VARCHAR(50),
    ltv_ratio                NUMERIC(5,2) NOT NULL CHECK (ltv_ratio > 0 AND ltv_ratio <= 100),
    charge_status            VARCHAR(15) NOT NULL DEFAULT 'PENDING'
                                CHECK (charge_status IN ('PENDING','REGISTERED','RELEASED')),
    cersai_registration_ref  VARCHAR(50),
    mortgage_deed_ref        VARCHAR(50),
    insurance_policy_ref     VARCHAR(50),
    legal_verification_status VARCHAR(15) DEFAULT 'PENDING'
                                CHECK (legal_verification_status IN ('PENDING','VERIFIED','REJECTED')),
    version                  INT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_mortgage_account ON mortgage(account_id);
CREATE INDEX idx_mortgage_charge_status ON mortgage(charge_status);

CREATE TRIGGER trg_mortgage_updated_at
    BEFORE UPDATE ON mortgage
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE mortgage_valuation_history (
    valuation_id             BIGSERIAL PRIMARY KEY,
    mortgage_id              BIGINT NOT NULL REFERENCES mortgage(mortgage_id),
    valuation_amount         NUMERIC(15,2) NOT NULL CHECK (valuation_amount > 0),
    valuation_date           DATE NOT NULL,
    valuer_ref               VARCHAR(50),
    valuation_type           VARCHAR(20) NOT NULL
                                CHECK (valuation_type IN ('INITIAL','PERIODIC_REVIEW','TRIGGER_BASED')),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_valuation_history_mortgage ON mortgage_valuation_history(mortgage_id, valuation_date);
