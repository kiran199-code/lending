-- Runs once, on first container start (docker-entrypoint-initdb.d), against the POSTGRES_DB
-- database created by the postgres image itself (see docker-compose.yml: POSTGRES_DB=lending).
-- Provisions the two application roles each service's own application.yml already defaults to,
-- so docker-compose works with zero extra env overrides beyond DB_HOST/SPRING_DATASOURCE_URL.

CREATE ROLE lending_app WITH LOGIN PASSWORD 'changeme';
GRANT ALL PRIVILEGES ON DATABASE lending TO lending_app;
GRANT ALL ON SCHEMA public TO lending_app;

CREATE ROLE lending_batch WITH LOGIN PASSWORD 'lending_batch';
CREATE SCHEMA IF NOT EXISTS batch AUTHORIZATION lending_batch;
GRANT ALL PRIVILEGES ON DATABASE lending TO lending_batch;

-- lending-batch-service's account-range readers query public.loan_account directly (see
-- AbstractAccountRangeChunkJob) -- it needs read access into lending-service's schema too.
GRANT USAGE ON SCHEMA public TO lending_batch;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO lending_batch;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO lending_batch;
