-- V6: EMI / payment transactions — partitioned by value_date.

CREATE TABLE emi_transaction (
    transaction_id           BIGSERIAL,
    account_id               BIGINT NOT NULL REFERENCES loan_account(account_id),
    installment_number       INT,
    transaction_type         VARCHAR(20) NOT NULL
                                CHECK (transaction_type IN ('EMI','PART_PAYMENT','FORECLOSURE','CHARGE')),
    amount                   NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    principal_paid           NUMERIC(15,2),
    interest_paid            NUMERIC(15,2),
    payment_channel          VARCHAR(20) NOT NULL
                                CHECK (payment_channel IN ('NACH','MANUAL','ONLINE')),
    payment_status           VARCHAR(15) NOT NULL
                                CHECK (payment_status IN ('SUCCESS','FAILED','PENDING')),
    value_date               DATE NOT NULL,
    idempotency_key          VARCHAR(100),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (transaction_id, value_date)
) PARTITION BY RANGE (value_date);

CREATE TABLE emi_transaction_2026_07 PARTITION OF emi_transaction
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE emi_transaction_2026_08 PARTITION OF emi_transaction
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE emi_transaction_2026_09 PARTITION OF emi_transaction
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

CREATE INDEX idx_emi_txn_account ON emi_transaction(account_id, value_date);
-- Idempotency: same idempotency_key must not be processed twice for a given account
CREATE UNIQUE INDEX idx_emi_txn_idempotency ON emi_transaction(account_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
