-- V8: Cross-cutting support tables — idempotency guard for API requests
-- (separate from the emi_transaction-specific idempotency index in V6,
-- this one is generic for any mutating endpoint), and the ledger outbox
-- pattern for reliable GL posting under transient ledger downtime.

CREATE TABLE processed_request (
    idempotency_key          VARCHAR(100) PRIMARY KEY,
    endpoint                 VARCHAR(100) NOT NULL,
    account_id               BIGINT,
    response_status          INT,
    response_body            JSONB,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_processed_request_account ON processed_request(account_id);

CREATE TABLE ledger_outbox (
    outbox_id                BIGSERIAL PRIMARY KEY,
    account_id               BIGINT NOT NULL REFERENCES loan_account(account_id),
    event_type               VARCHAR(30) NOT NULL
                                CHECK (event_type IN ('DISBURSEMENT','EMI_POSTING','FORECLOSURE','CHARGE_REVERSAL')),
    payload                  JSONB NOT NULL,
    posting_status           VARCHAR(15) NOT NULL DEFAULT 'PENDING'
                                CHECK (posting_status IN ('PENDING','POSTED','FAILED')),
    retry_count              INT NOT NULL DEFAULT 0,
    last_attempted_at        TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_outbox_pending ON ledger_outbox(posting_status, created_at)
    WHERE posting_status = 'PENDING';
