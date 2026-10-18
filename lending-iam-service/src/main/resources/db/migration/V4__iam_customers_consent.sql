-- IAM customer identities: links to customer records and accounts, consent purposes and records.
-- IAM never stores a customer's loans, balances, address or documents: only the references below.

CREATE TABLE customer_link (
    link_id       BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES app_user (user_id),
    customer_id   VARCHAR(40)  NOT NULL,
    link_type     VARCHAR(10)  NOT NULL CHECK (link_type IN ('CUSTOMER', 'ACCOUNT')),
    account_ref   VARCHAR(40),
    relationship  VARCHAR(12)  NOT NULL CHECK (relationship IN ('OWNER', 'CO_BORROWER', 'GUARANTOR', 'DELEGATE')),
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'REJECTED', 'REVOKED')),
    valid_from    DATE         NOT NULL DEFAULT CURRENT_DATE,
    valid_to      DATE,
    created_by    VARCHAR(60)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    approved_by   VARCHAR(60),
    approved_at   TIMESTAMPTZ,
    revoked_by    VARCHAR(60),
    revoked_at    TIMESTAMPTZ,
    reason        VARCHAR(300),
    CHECK ((link_type = 'ACCOUNT') = (account_ref IS NOT NULL)),
    CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CHECK (approved_by IS NULL OR approved_by <> created_by)
);
CREATE INDEX ix_customer_link_user ON customer_link (user_id, status);
CREATE INDEX ix_customer_link_pending ON customer_link (status) WHERE status = 'PENDING_APPROVAL';

CREATE TABLE consent_purpose (
    purpose_code  VARCHAR(30)  PRIMARY KEY,
    description   VARCHAR(300) NOT NULL,
    version       INT          NOT NULL DEFAULT 1 CHECK (version >= 1),
    mandatory     BOOLEAN      NOT NULL DEFAULT FALSE,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Append only: a change of mind is a new row.
CREATE TABLE consent_record (
    record_id     BIGSERIAL PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES app_user (user_id),
    purpose_code  VARCHAR(30) NOT NULL REFERENCES consent_purpose (purpose_code),
    version       INT         NOT NULL,
    granted       BOOLEAN     NOT NULL,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    client_id     VARCHAR(80),
    ip            VARCHAR(64)
);
CREATE INDEX ix_consent_record_user ON consent_record (user_id, purpose_code, record_id DESC);

CREATE FUNCTION consent_record_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'consent_record is append-only: % is not allowed', TG_OP USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_consent_record_no_change BEFORE UPDATE OR DELETE ON consent_record
    FOR EACH ROW EXECUTE FUNCTION consent_record_immutable();

INSERT INTO consent_purpose (purpose_code, description, version, mandatory) VALUES
  ('TERMS',     'I accept the terms of use of the customer portal.', 1, TRUE),
  ('PRIVACY',   'I have read the privacy notice and agree to the processing of my data to provide my loan services.', 1, TRUE),
  ('MARKETING', 'I agree to receive information about other products and offers.', 1, FALSE);
