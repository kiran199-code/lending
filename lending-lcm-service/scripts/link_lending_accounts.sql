-- Run AFTER the LCM bridge load succeeded (sanctions.json posted). Run against the LENDING database.
--
--     psql "$LENDING_DB_URL" -v ON_ERROR_STOP=1 -f scripts/link_lending_accounts.sql
--
-- Points existing, open loan accounts at their implicit facility SANC-<sanction_ref_id> so lending starts reporting
-- repayments and closure to LCM for them. Needs lending migration V19 (limit_facility_ref column). It only fills accounts
-- that have no facility yet, so it is safe to repeat. Switch lending.lcm.mode to shadow only after this has run.

UPDATE loan_account
   SET limit_facility_ref = 'SANC-' || sanction_ref_id
 WHERE limit_facility_ref IS NULL
   AND account_status NOT IN ('CLOSED', 'FORECLOSED', 'WRITTEN_OFF');
