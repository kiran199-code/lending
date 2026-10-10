-- Optional hardening: a dedicated SELECT-only database role for lending-admin-service.
-- (The service already opens every connection read-only; this makes it impossible at the DB level too.)
-- Run once as a superuser / the database owner, then set DB_USER=lending_admin, DB_PASSWORD=<pwd>.
CREATE ROLE lending_admin WITH LOGIN PASSWORD 'change-me';
GRANT CONNECT ON DATABASE lending TO lending_admin;

GRANT USAGE ON SCHEMA public TO lending_admin;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO lending_admin;
-- Tables/partitions that lending-service (role lending_app) creates later, e.g. the monthly partitions:
ALTER DEFAULT PRIVILEGES FOR ROLE lending_app IN SCHEMA public GRANT SELECT ON TABLES TO lending_admin;

-- Batch monitoring page (optional; skip these lines if the batch service is not deployed).
GRANT USAGE ON SCHEMA batch TO lending_admin;
GRANT SELECT ON ALL TABLES IN SCHEMA batch TO lending_admin;
ALTER DEFAULT PRIVILEGES FOR ROLE lending_batch IN SCHEMA batch GRANT SELECT ON TABLES TO lending_admin;
