-- V3: enrolment. Seat numbers can be reused before activation, fee receipts are recorded, and security rows carry
-- who verified them. Runs in schema 'chit'.

-- A ticket that is withdrawn before the group starts (status EXITED) gives its number back, so the group can still
-- end up with tickets 1..N. Only live tickets must have distinct numbers.
ALTER TABLE chit_ticket DROP CONSTRAINT chit_ticket_group_id_ticket_no_key;
CREATE UNIQUE INDEX uq_chit_ticket_live ON chit_ticket(group_id, ticket_no) WHERE status <> 'EXITED';
ALTER TABLE chit_ticket ADD COLUMN exit_reason VARCHAR(120);
ALTER TABLE chit_ticket ADD COLUMN exited_on DATE;

ALTER TABLE chit_group ADD COLUMN cancel_reason VARCHAR(200);
ALTER TABLE chit_group ADD COLUMN cancelled_by VARCHAR(60);
ALTER TABLE chit_group ADD COLUMN activated_by VARCHAR(60);

-- Enrolment and registration fees as received at enrolment. A refund marks the row; rows are never deleted.
CREATE TABLE chit_fee_receipt (
    id           BIGSERIAL PRIMARY KEY,
    ticket_id    BIGINT        NOT NULL REFERENCES chit_ticket(id),
    fee_kind     VARCHAR(12)   NOT NULL CHECK (fee_kind IN ('ENROLMENT','REGISTRATION')),
    amount       NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    status       VARCHAR(10)   NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','REFUNDED')),
    received_on  DATE          NOT NULL,
    refunded_on  DATE,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   VARCHAR(60)   NOT NULL,
    UNIQUE (ticket_id, fee_kind),
    CHECK ((status = 'REFUNDED') = (refunded_on IS NOT NULL))
);

-- Security: one live row per ticket and kind; verification is recorded.
ALTER TABLE chit_security ADD COLUMN received_on DATE;
ALTER TABLE chit_security ADD COLUMN verified_by VARCHAR(60);
ALTER TABLE chit_security ADD COLUMN verified_at TIMESTAMPTZ;
ALTER TABLE chit_security ADD COLUMN returned_on DATE;
-- for a lien: the reference of the subscriber's deposit that was marked
ALTER TABLE chit_security ADD COLUMN reference VARCHAR(60);
CREATE UNIQUE INDEX uq_chit_security_live ON chit_security(ticket_id, kind) WHERE status <> 'RETURNED';
ALTER TABLE chit_security ADD CONSTRAINT ck_security_verified CHECK (status <> 'VERIFIED' OR verified_by IS NOT NULL);
