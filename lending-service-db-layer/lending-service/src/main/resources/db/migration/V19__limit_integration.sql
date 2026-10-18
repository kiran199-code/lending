-- V19: link loan accounts to a credit facility in the separate Limits & Collateral Management (LCM) service, and a
-- retry queue for the calls that must reach it after the loan transaction has committed.
--
-- Nothing here changes behaviour on its own. Both new loan_account columns are NULL for every existing account, and
-- the lending.lcm.mode setting defaults to "off", in which case the application never reads or writes either column
-- or the queue.
--
--  * limit_facility_ref     the LCM facility this account draws on (set at creation, or in bulk by
--                            lending-lcm-service/scripts/link_lending_accounts.sql). NULL = the account is not limit-controlled.
--  * limit_reservation_ref  the LCM reservation taken when the account was disbursed (kept for audit and support).
--  * limit_outbox           one row per call still owed to LCM: confirming a disbursement, reporting a principal
--                            repayment, closing the position. A row is written in the same transaction as the loan
--                            change, so the call can neither be lost nor sent for a change that rolled back. The
--                            idempotency_key is sent to LCM as its Idempotency-Key, so redelivery never applies twice.
--                            Rows for one account are delivered strictly in id order.

ALTER TABLE loan_account
    ADD COLUMN limit_facility_ref    VARCHAR(40),
    ADD COLUMN limit_reservation_ref VARCHAR(40);

CREATE INDEX idx_loan_account_limit_facility ON loan_account(limit_facility_ref) WHERE limit_facility_ref IS NOT NULL;

CREATE TABLE limit_outbox (
    id               BIGSERIAL     PRIMARY KEY,
    account_id       BIGINT        NOT NULL REFERENCES loan_account(account_id),
    operation        VARCHAR(30)   NOT NULL CHECK (operation IN ('CONFIRM_DISBURSEMENT', 'REPAY', 'CLOSE')),
    facility_ref     VARCHAR(40)   NOT NULL,
    reservation_ref  VARCHAR(40),
    amount           NUMERIC(15,2) CHECK (amount IS NULL OR amount > 0),
    currency_code    VARCHAR(3)    NOT NULL,
    branch_code      VARCHAR(10)   NOT NULL,
    business_date    DATE          NOT NULL,
    reason           VARCHAR(200),
    idempotency_key  VARCHAR(100)  NOT NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'SKIPPED', 'DEAD')),
    attempts         INTEGER       NOT NULL DEFAULT 0,
    last_error       VARCHAR(500),
    next_attempt_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    sent_at          TIMESTAMPTZ,
    CONSTRAINT uq_limit_outbox_key UNIQUE (idempotency_key),
    CONSTRAINT ck_limit_outbox_amount CHECK (operation = 'CLOSE' OR amount IS NOT NULL)
);

CREATE INDEX idx_limit_outbox_due     ON limit_outbox(next_attempt_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_limit_outbox_account ON limit_outbox(account_id, id);
