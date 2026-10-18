-- IAM core: identities, one-time tokens, OAuth clients, signing keys, audit trail.
-- Runs in schema "iam" (Flyway default schema; every connection has search_path = iam).

CREATE TABLE app_user (
    user_id               BIGSERIAL PRIMARY KEY,
    username              VARCHAR(60)  NOT NULL,
    user_kind             VARCHAR(10)  NOT NULL CHECK (user_kind IN ('STAFF', 'CUSTOMER')),
    display_name          VARCHAR(120) NOT NULL,
    email                 VARCHAR(160),
    mobile                VARCHAR(30),
    customer_id           VARCHAR(40),
    status                VARCHAR(20)  NOT NULL
                          CHECK (status IN ('PENDING_ACTIVATION', 'ACTIVE', 'LOCKED', 'SUSPENDED', 'EXPIRED', 'OFFBOARDED')),
    password_hash         VARCHAR(120),
    password_changed_at   TIMESTAMPTZ,
    must_change_password  BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_attempts       INT          NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    mfa_enabled           BOOLEAN      NOT NULL DEFAULT FALSE,
    mfa_secret_enc        TEXT,
    mfa_pending_enc       TEXT,
    mfa_last_step         BIGINT,
    tokens_valid_after    TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    bootstrap             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by            VARCHAR(60)  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_customer_has_customer_id CHECK (user_kind <> 'CUSTOMER' OR customer_id IS NOT NULL)
);
-- A username is unique ignoring case, never changes and is never reused (offboarded rows stay).
CREATE UNIQUE INDEX uq_app_user_username ON app_user (lower(username));
CREATE INDEX ix_app_user_kind_status ON app_user (user_kind, status);
CREATE INDEX ix_app_user_customer ON app_user (customer_id) WHERE customer_id IS NOT NULL;

CREATE TABLE password_history (
    history_id     BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES app_user (user_id),
    password_hash  VARCHAR(120) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_password_history_user ON password_history (user_id, history_id DESC);

CREATE TABLE one_time_token (
    token_id    BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES app_user (user_id),
    purpose     VARCHAR(20)  NOT NULL CHECK (purpose IN ('ACTIVATION', 'TEMP_PASSWORD')),
    token_hash  CHAR(64)     NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_one_time_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_one_time_token_user ON one_time_token (user_id, purpose);

CREATE TABLE mfa_recovery_code (
    code_id     BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES app_user (user_id),
    code_hash   CHAR(64)    NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_mfa_recovery_code UNIQUE (user_id, code_hash)
);

CREATE TABLE oauth_client (
    client_id              VARCHAR(80)  PRIMARY KEY,
    client_name            VARCHAR(120) NOT NULL,
    client_kind            VARCHAR(12)  NOT NULL CHECK (client_kind IN ('CONFIDENTIAL', 'PUBLIC')),
    secret_hash            VARCHAR(120),
    previous_secret_hash   VARCHAR(120),
    previous_valid_until   TIMESTAMPTZ,
    grant_types            VARCHAR(200) NOT NULL,
    redirect_uris          TEXT         NOT NULL DEFAULT '',
    post_logout_uris       TEXT         NOT NULL DEFAULT '',
    scopes                 VARCHAR(300) NOT NULL DEFAULT 'openid profile',
    access_ttl_seconds     INT          NOT NULL DEFAULT 900,
    refresh_ttl_seconds    INT          NOT NULL DEFAULT 28800,
    status                 VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    tokens_valid_after     TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by             VARCHAR(60)  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_public_client_no_secret CHECK (client_kind <> 'PUBLIC' OR secret_hash IS NULL),
    CONSTRAINT ck_confidential_needs_secret CHECK (client_kind <> 'CONFIDENTIAL' OR secret_hash IS NOT NULL),
    CONSTRAINT ck_public_client_grants CHECK (client_kind <> 'PUBLIC' OR grant_types NOT LIKE '%client_credentials%')
);

CREATE TABLE signing_key (
    key_id        VARCHAR(40) PRIMARY KEY,
    public_pem    TEXT        NOT NULL,
    private_enc   TEXT        NOT NULL,
    status        VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'RETIRED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    retired_at    TIMESTAMPTZ,
    retire_after  TIMESTAMPTZ
);
-- At most one active key.
CREATE UNIQUE INDEX uq_signing_key_one_active ON signing_key ((status)) WHERE status = 'ACTIVE';

-- Append-only, hash-chained audit trail. The hash is computed by the application inside the same
-- transaction as the change it records (see AuditService); the trigger makes the rows immutable.
CREATE TABLE audit_log (
    audit_id        BIGSERIAL PRIMARY KEY,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    actor_type      VARCHAR(10)  NOT NULL CHECK (actor_type IN ('USER', 'CLIENT', 'SYSTEM', 'ANONYMOUS')),
    actor           VARCHAR(80)  NOT NULL,
    action          VARCHAR(60)  NOT NULL,
    target_type     VARCHAR(30),
    target_id       VARCHAR(80),
    outcome         VARCHAR(10)  NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    ip              VARCHAR(64),
    correlation_id  VARCHAR(64),
    detail          TEXT         NOT NULL DEFAULT '{}',
    prev_hash       CHAR(64)     NOT NULL,
    row_hash        CHAR(64)     NOT NULL
);
CREATE INDEX ix_audit_log_time ON audit_log (occurred_at);
CREATE INDEX ix_audit_log_actor ON audit_log (actor, audit_id DESC);
CREATE INDEX ix_audit_log_target ON audit_log (target_type, target_id, audit_id DESC);
CREATE INDEX ix_audit_log_action ON audit_log (action, audit_id DESC);

CREATE FUNCTION audit_log_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
CREATE TRIGGER trg_audit_log_no_truncate BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_immutable();
