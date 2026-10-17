-- V14: demand records, payment allocation and the account fields that go with them.
--
-- A demand is what falls due on an instalment's due date: a principal part and an interest part, each with the
-- amount paid so far. Payments are applied to demands oldest first (see PaymentWaterfall) and every application
-- is kept as an allocation line. days_past_due is stored by the delinquency classification run;
-- advance_balance holds money received that is not yet applied to any demand.

ALTER TABLE loan_account
    ADD COLUMN days_past_due    INT           NOT NULL DEFAULT 0 CHECK (days_past_due >= 0),
    ADD COLUMN advance_balance  NUMERIC(15,2) NOT NULL DEFAULT 0 CHECK (advance_balance >= 0);

CREATE TABLE loan_demand (
    demand_id           BIGSERIAL PRIMARY KEY,
    account_id          BIGINT        NOT NULL REFERENCES loan_account(account_id),
    schedule_version    INT           NOT NULL CHECK (schedule_version > 0),
    installment_number  INT           NOT NULL CHECK (installment_number > 0),
    due_date            DATE          NOT NULL,
    principal_due       NUMERIC(15,2) NOT NULL CHECK (principal_due >= 0),
    interest_due        NUMERIC(15,2) NOT NULL CHECK (interest_due >= 0),
    principal_paid      NUMERIC(15,2) NOT NULL DEFAULT 0 CHECK (principal_paid >= 0),
    interest_paid       NUMERIC(15,2) NOT NULL DEFAULT 0 CHECK (interest_paid >= 0),
    status              VARCHAR(12)   NOT NULL DEFAULT 'OPEN'
                            CHECK (status IN ('OPEN','PART_PAID','CLEARED')),
    cleared_date        DATE,
    raised_on           DATE          NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- One demand per instalment of an account: raising again is a no-op, which makes the daily job safe to re-run.
    UNIQUE (account_id, installment_number),
    CHECK (principal_paid <= principal_due AND interest_paid <= interest_due)
);

CREATE INDEX idx_loan_demand_open ON loan_demand(account_id, installment_number) WHERE status <> 'CLEARED';
CREATE INDEX idx_loan_demand_due_date ON loan_demand(due_date);

CREATE TRIGGER trg_loan_demand_updated_at
    BEFORE UPDATE ON loan_demand
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Where each payment went. transaction_id is not a foreign key because emi_transaction is partitioned and keyed by
-- (transaction_id, value_date); it is empty for lines created when an advance is applied to a new demand.
CREATE TABLE payment_allocation (
    allocation_id       BIGSERIAL PRIMARY KEY,
    account_id          BIGINT        NOT NULL REFERENCES loan_account(account_id),
    demand_id           BIGINT        REFERENCES loan_demand(demand_id),
    installment_number  INT,
    transaction_id      BIGINT,
    value_date          DATE          NOT NULL,
    bucket              VARCHAR(10)   NOT NULL CHECK (bucket IN ('PENALTY','INTEREST','PRINCIPAL','ADVANCE')),
    source              VARCHAR(10)   NOT NULL CHECK (source IN ('PAYMENT','ADVANCE')),
    amount              NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_allocation_txn ON payment_allocation(transaction_id, value_date);
CREATE INDEX idx_payment_allocation_account ON payment_allocation(account_id, allocation_id);
