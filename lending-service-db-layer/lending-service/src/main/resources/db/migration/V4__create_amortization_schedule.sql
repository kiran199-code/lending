-- V4: Amortization schedule — one row per installment.
-- schedule_version increments whenever the schedule is regenerated
-- (rate reset, part-payment). Old versions are retained for audit,
-- never deleted — "current" schedule = highest version per account.

CREATE TABLE amortization_schedule (
    schedule_id              BIGSERIAL PRIMARY KEY,
    account_id               BIGINT NOT NULL REFERENCES loan_account(account_id),
    installment_number       INT NOT NULL CHECK (installment_number > 0),
    due_date                 DATE NOT NULL,
    principal_component      NUMERIC(15,2) NOT NULL,
    interest_component       NUMERIC(15,2) NOT NULL CHECK (interest_component >= 0),
    outstanding_balance      NUMERIC(15,2) NOT NULL,
    installment_status       VARCHAR(15) NOT NULL DEFAULT 'PENDING'
                                CHECK (installment_status IN ('PENDING','PAID','OVERDUE')),
    schedule_version         INT NOT NULL DEFAULT 1 CHECK (schedule_version > 0),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(account_id, installment_number, schedule_version)
);

CREATE INDEX idx_schedule_account_due ON amortization_schedule(account_id, due_date);
CREATE INDEX idx_schedule_account_version ON amortization_schedule(account_id, schedule_version);
CREATE INDEX idx_schedule_status ON amortization_schedule(installment_status) WHERE installment_status = 'OVERDUE';
