-- V2: collateral register, valuations (maker-checker), facility allocations and per-type configuration.

CREATE TABLE collateral_type_config (
    type_code               VARCHAR(30) PRIMARY KEY,
    description             VARCHAR(120) NOT NULL,
    default_haircut_pct     NUMERIC(5,2) NOT NULL CHECK (default_haircut_pct >= 0 AND default_haircut_pct < 100),
    max_ltv_pct             NUMERIC(5,2) NOT NULL CHECK (max_ltv_pct > 0 AND max_ltv_pct <= 100),
    valuation_validity_days INT NOT NULL CHECK (valuation_validity_days >= 0),  -- 0 = valuation never goes stale
    requires_charge         BOOLEAN NOT NULL,
    requires_insurance      BOOLEAN NOT NULL,
    active                  BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE collateral (
    id                  BIGSERIAL PRIMARY KEY,
    collateral_ref      VARCHAR(40) NOT NULL UNIQUE,
    customer_id         BIGINT NOT NULL,
    collateral_type     VARCHAR(30) NOT NULL REFERENCES collateral_type_config(type_code),
    description         VARCHAR(400) NOT NULL,
    currency_code       CHAR(3) NOT NULL,
    status              VARCHAR(10) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','SUSPENDED','RELEASED')),
    legal_status        VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (legal_status IN ('PENDING','VERIFIED','FAILED')),
    charge_registered   BOOLEAN NOT NULL DEFAULT FALSE,
    charge_ref          VARCHAR(60),
    charge_registered_on DATE,
    prior_charge_amount NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (prior_charge_amount >= 0),
    insurance_expiry    DATE,
    haircut_pct         NUMERIC(5,2) CHECK (haircut_pct IS NULL OR (haircut_pct >= 0 AND haircut_pct < 100)),   -- null = type default
    max_ltv_pct         NUMERIC(5,2) CHECK (max_ltv_pct IS NULL OR (max_ltv_pct > 0 AND max_ltv_pct <= 100)),  -- null = type default
    lending_mortgage_id BIGINT,                  -- bridge link to lending.mortgage
    source              VARCHAR(10) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','BRIDGE')),
    created_by          VARCHAR(60) NOT NULL,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_collateral_mortgage ON collateral(lending_mortgage_id) WHERE lending_mortgage_id IS NOT NULL;
CREATE INDEX idx_collateral_customer ON collateral(customer_id);
CREATE TRIGGER trg_collateral_updated_at BEFORE UPDATE ON collateral FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE collateral_valuation (
    id                BIGSERIAL PRIMARY KEY,
    collateral_id     BIGINT NOT NULL REFERENCES collateral(id),
    valuation_date    DATE NOT NULL,
    market_value      NUMERIC(19,2) NOT NULL CHECK (market_value > 0),
    forced_sale_value NUMERIC(19,2) CHECK (forced_sale_value IS NULL OR forced_sale_value > 0),
    valuer_ref        VARCHAR(60),
    source            VARCHAR(10) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','BRIDGE')),
    status            VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    maker             VARCHAR(60) NOT NULL,
    checker           VARCHAR(60),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at        TIMESTAMPTZ
);
CREATE INDEX idx_valuation_collateral ON collateral_valuation(collateral_id, valuation_date);
CREATE UNIQUE INDEX uq_valuation_bridge ON collateral_valuation(collateral_id, valuation_date, source)
    WHERE source = 'BRIDGE';

CREATE TABLE collateral_allocation (
    id             BIGSERIAL PRIMARY KEY,
    collateral_id  BIGINT NOT NULL REFERENCES collateral(id),
    facility_id    BIGINT NOT NULL REFERENCES facility(id),
    allocation_pct NUMERIC(5,2) NOT NULL CHECK (allocation_pct > 0 AND allocation_pct <= 100),
    status         VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RELEASED')),
    created_by     VARCHAR(60) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at    TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_allocation_active ON collateral_allocation(collateral_id, facility_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_allocation_facility ON collateral_allocation(facility_id) WHERE status = 'ACTIVE';

-- Defaults below are illustrative placeholders. Regulatory/credit-policy values must be confirmed by Credit Policy.
INSERT INTO collateral_type_config (type_code, description, default_haircut_pct, max_ltv_pct, valuation_validity_days, requires_charge, requires_insurance) VALUES
 ('RESIDENTIAL_PROPERTY', 'Residential property (mortgage)',   20.00, 75.00, 365, TRUE,  TRUE),
 ('COMMERCIAL_PROPERTY',  'Commercial property (mortgage)',    30.00, 65.00, 365, TRUE,  TRUE),
 ('FIXED_DEPOSIT',        'Bank fixed deposit (lien marked)',   0.00, 90.00,   0, TRUE,  FALSE),
 ('VEHICLE',              'Vehicle (hypothecation)',           30.00, 70.00, 365, TRUE,  TRUE),
 ('LISTED_SECURITIES',    'Listed shares / mutual funds',      40.00, 50.00,  30, TRUE,  FALSE),
 ('GOLD',                 'Gold ornaments / bullion',          25.00, 75.00,  30, FALSE, FALSE);
