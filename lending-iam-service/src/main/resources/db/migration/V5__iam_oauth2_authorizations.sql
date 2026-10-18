-- Spring Authorization Server 1.3 storage for issued authorizations (codes, access, refresh and id tokens).
-- Layout follows the library's reference schema (oauth2-authorization-schema.sql, 1.3.x) with text columns for the
-- large values, which is what the JDBC service reads and writes on PostgreSQL. Consent is not used (first-party clients),
-- so there is no consent table. If the library is upgraded, compare with its schema file before upgrading.
CREATE TABLE oauth2_authorization (
    id                            VARCHAR(100)  NOT NULL,
    registered_client_id          VARCHAR(100)  NOT NULL,
    principal_name                VARCHAR(200)  NOT NULL,
    authorization_grant_type      VARCHAR(100)  NOT NULL,
    authorized_scopes             VARCHAR(1000),
    attributes                    TEXT,
    state                         VARCHAR(500),
    authorization_code_value      TEXT,
    authorization_code_issued_at  TIMESTAMP,
    authorization_code_expires_at TIMESTAMP,
    authorization_code_metadata   TEXT,
    access_token_value            TEXT,
    access_token_issued_at        TIMESTAMP,
    access_token_expires_at       TIMESTAMP,
    access_token_metadata         TEXT,
    access_token_type             VARCHAR(100),
    access_token_scopes           VARCHAR(1000),
    oidc_id_token_value           TEXT,
    oidc_id_token_issued_at       TIMESTAMP,
    oidc_id_token_expires_at      TIMESTAMP,
    oidc_id_token_metadata        TEXT,
    refresh_token_value           TEXT,
    refresh_token_issued_at       TIMESTAMP,
    refresh_token_expires_at      TIMESTAMP,
    refresh_token_metadata        TEXT,
    user_code_value               TEXT,
    user_code_issued_at           TIMESTAMP,
    user_code_expires_at          TIMESTAMP,
    user_code_metadata            TEXT,
    device_code_value             TEXT,
    device_code_issued_at         TIMESTAMP,
    device_code_expires_at        TIMESTAMP,
    device_code_metadata          TEXT,
    PRIMARY KEY (id)
);
CREATE INDEX ix_oauth2_auth_principal ON oauth2_authorization (principal_name);
CREATE INDEX ix_oauth2_auth_client ON oauth2_authorization (registered_client_id);
