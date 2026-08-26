-- V1: Core loan_account table (shared by Unsecured and Mortgage tracks)
-- Note: mortgage_id is a plain column here (no FK yet) because mortgage table
-- references loan_account.account_id, creating a circular dependency.
-- The FK is added in V3 once mortgage exists.

CREATE TABLE loan_account (
    account_id              BIGSERIAL PRIMARY KEY,
    account_number           VARCHAR(20) NOT NULL UNIQUE,
    customer_id              BIGINT NOT NULL,
    sanction_ref_id          BIGINT NOT NULL UNIQUE,
    product_code             VARCHAR(10) NOT NULL,
    account_type             VARCHAR(15) NOT NULL
                                CHECK (account_type IN ('UNSECURED','MORTGAGE')),
    sanctioned_amount        NUMERIC(15,2) NOT NULL CHECK (sanctioned_amount > 0),
    disbursed_amount         NUMERIC(15,2),
    tenure_months            INT NOT NULL CHECK (tenure_months > 0),
    rate_type                VARCHAR(10) NOT NULL
                                CHECK (rate_type IN ('FIXED','FLOATING')),
    interest_rate            NUMERIC(6,4) NOT NULL CHECK (interest_rate >= 0),
    benchmark_rate_ref       VARCHAR(20),
    spread                   NUMERIC(6,4),
    day_count_convention     VARCHAR(10) NOT NULL DEFAULT 'ACT/365'
                                CHECK (day_count_convention IN ('ACT/365','ACT/ACT','30/360')),
    emi_amount               NUMERIC(15,2),
    emi_start_date           DATE,
    account_status           VARCHAR(30) NOT NULL DEFAULT 'PENDING_DISBURSEMENT'
                                CHECK (account_status IN (
                                    'PENDING_DISBURSEMENT','ACTIVE',
                                    'NPA_SMA0','NPA_SMA1','NPA_SMA2',
                                    'NPA_SUBSTANDARD','NPA_DOUBTFUL','NPA_LOSS',
                                    'CLOSED','FORECLOSED','WRITTEN_OFF')),
    mortgage_id              BIGINT,                       -- FK added in V3
    version                  INT NOT NULL DEFAULT 0,        -- optimistic locking (JPA @Version)
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- account_type <-> mortgage_id consistency: unsecured accounts must not carry a mortgage_id,
-- mortgage accounts must eventually have one (enforced at application layer post-creation,
-- since mortgage_id is null at account creation and populated once mortgage record is linked)
ALTER TABLE loan_account
    ADD CONSTRAINT chk_unsecured_no_mortgage
    CHECK (account_type != 'UNSECURED' OR mortgage_id IS NULL);

CREATE INDEX idx_loan_account_customer ON loan_account(customer_id);
CREATE INDEX idx_loan_account_status ON loan_account(account_status);
CREATE INDEX idx_loan_account_product ON loan_account(product_code);

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_loan_account_updated_at
    BEFORE UPDATE ON loan_account
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
