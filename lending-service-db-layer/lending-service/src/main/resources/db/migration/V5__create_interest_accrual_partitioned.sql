-- V5: Daily interest accrual — partitioned by month (fastest-growing table).
-- UNIQUE(account_id, business_date) is the idempotency guard: a re-run of the
-- daily accrual batch for the same business_date will not create duplicates
-- (paired with ON CONFLICT DO NOTHING in the batch writer).

CREATE TABLE interest_accrual (
    accrual_id               BIGSERIAL,
    account_id               BIGINT NOT NULL REFERENCES loan_account(account_id),
    business_date            DATE NOT NULL,
    accrued_amount           NUMERIC(15,4) NOT NULL,
    outstanding_principal    NUMERIC(15,2) NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (accrual_id, business_date),
    UNIQUE (account_id, business_date)
) PARTITION BY RANGE (business_date);

-- Bootstrap partitions: current month + next 2 months.
-- Ongoing partition creation is handled by the function + scheduled call in V7.
CREATE TABLE interest_accrual_2026_07 PARTITION OF interest_accrual
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE interest_accrual_2026_08 PARTITION OF interest_accrual
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE interest_accrual_2026_09 PARTITION OF interest_accrual
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

CREATE INDEX idx_accrual_account ON interest_accrual(account_id, business_date);
