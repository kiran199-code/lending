-- V15: penal interest.
--
-- Penal interest is a flat daily charge on the principal and interest that stay unpaid after a demand's due date
-- (plus grace days). It is set per loan product, accrues once per demand per business date into penal_interest, and
-- is held on the demand (penal_accrued, kept to 4 decimals; the amount owed is that rounded to 2 decimals less
-- penal_paid). It is never added to the interest rate, never compounds and does not change the schedule.
-- The defaults below are placeholders: 24% a year, no grace days, no cap, switched on. Edit them per product.

ALTER TABLE loan_product
    ADD COLUMN penal_interest_enabled BOOLEAN       NOT NULL DEFAULT TRUE,
    ADD COLUMN penal_interest_rate    NUMERIC(6,4)  NOT NULL DEFAULT 24.0000
                                          CHECK (penal_interest_rate >= 0 AND penal_interest_rate < 100),
    ADD COLUMN penal_grace_days       INT           NOT NULL DEFAULT 0 CHECK (penal_grace_days >= 0),
    -- Most penal interest one demand may collect, as a % of that demand's principal + interest due. NULL = no cap.
    ADD COLUMN penal_cap_percent      NUMERIC(6,2)  CHECK (penal_cap_percent IS NULL OR penal_cap_percent > 0);

ALTER TABLE loan_demand
    ADD COLUMN penal_accrued NUMERIC(15,4) NOT NULL DEFAULT 0 CHECK (penal_accrued >= 0),
    ADD COLUMN penal_paid    NUMERIC(15,2) NOT NULL DEFAULT 0 CHECK (penal_paid >= 0);

-- One row per demand per business date on which penal interest was charged.
CREATE TABLE penal_interest (
    penal_id        BIGSERIAL PRIMARY KEY,
    account_id      BIGINT        NOT NULL REFERENCES loan_account(account_id),
    demand_id       BIGINT        NOT NULL REFERENCES loan_demand(demand_id),
    business_date   DATE          NOT NULL,
    base_amount     NUMERIC(15,2) NOT NULL CHECK (base_amount > 0),
    annual_rate     NUMERIC(6,4)  NOT NULL,
    amount          NUMERIC(15,4) NOT NULL CHECK (amount >= 0),
    -- True from SUBSTANDARD onward: accrued but flagged for accounting (taking it to income is a finance decision).
    suspended       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Makes the daily job safe to re-run: a date is charged once per demand.
    UNIQUE (demand_id, business_date)
);

CREATE INDEX idx_penal_interest_account ON penal_interest(account_id, business_date);

-- What part of a payment went to penal interest (empty on payments made before this release).
ALTER TABLE emi_transaction ADD COLUMN penal_paid NUMERIC(15,2);
