-- READ-ONLY export of existing lending data as JSON for the LCM bridge API.
--
-- Run against the lending database (same Postgres, schema "public"; edit the search_path line if yours differs):
--
--     psql "$LENDING_DB_URL" -v ON_ERROR_STOP=1 -f scripts/bridge_from_lending.sql
--
-- It writes three files into the current directory and changes nothing in the database:
--     sanctions.json   -> POST /api/v1/lcm/bridge/lending/facilities
--     positions.json   -> POST /api/v1/lcm/bridge/lending/positions   (post AFTER sanctions)
--     mortgages.json   -> POST /api/v1/lcm/bridge/lending/collateral
-- Post them with curl, e.g.
--     curl -u lcm-service:$LCM_SERVICE_PASSWORD -H 'Content-Type: application/json' \
--          --data @sanctions.json http://localhost:8084/api/v1/lcm/bridge/lending/facilities
-- Every item is idempotent, so the load can be repeated; the response lists each item as CREATED / SKIPPED / FAILED.
-- When all three succeeded, run scripts/link_lending_accounts.sql against the lending database.
--
-- Mapping:
--   * each non-closed loan account's sanction -> implicit ACTIVE facility SANC-<sanction_ref_id>, limit = sanctioned_amount,
--     expiry = first EMI date (or creation date) + tenure; one loan per sanction today, so the facility is non-revolving
--   * each disbursed, non-closed loan -> opening position: drawn = disbursed amount,
--     outstanding = disbursed - successful principal repayments (the same figure lending reports to LCM from then on)
--   * each mortgage -> collateral MORT-<mortgage_id> in DRAFT (legal and charge status carried over, valuation history imported).
--     Lending holds no insurance expiry, so activation in LCM stays blocked until someone records it.

SET search_path = public;
\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on

\o sanctions.json
SELECT json_build_object('sanctions', COALESCE(json_agg(json_build_object(
           'sanctionRefId',    la.sanction_ref_id,
           'customerId',       la.customer_id,
           'productCode',      la.product_code,
           'currencyCode',     la.currency_code,
           'branchCode',       la.branch_code,
           'sanctionedAmount', la.sanctioned_amount,
           'expiryDate',       (COALESCE(la.emi_start_date, la.created_at::date) + (la.tenure_months || ' months')::interval)::date
       ) ORDER BY la.account_id), '[]'::json))
FROM loan_account la
WHERE la.account_status NOT IN ('CLOSED', 'FORECLOSED', 'WRITTEN_OFF');

\o positions.json
SELECT json_build_object('positions', COALESCE(json_agg(json_build_object(
           'sanctionRefId',        x.sanction_ref_id,
           'accountId',            x.account_id,
           'drawnAmount',          x.disbursed_amount,
           'outstandingPrincipal', GREATEST(x.disbursed_amount - x.repaid, 0)
       ) ORDER BY x.account_id), '[]'::json))
FROM (
    SELECT la.account_id, la.sanction_ref_id, la.disbursed_amount,
           COALESCE((SELECT SUM(t.principal_paid) FROM emi_transaction t
                      WHERE t.account_id = la.account_id AND t.payment_status = 'SUCCESS'), 0) AS repaid
    FROM loan_account la
    WHERE la.disbursed_amount IS NOT NULL AND la.disbursed_amount > 0
      AND la.account_status NOT IN ('PENDING_DISBURSEMENT', 'CLOSED', 'FORECLOSED', 'WRITTEN_OFF')
) x;

\o mortgages.json
SELECT json_build_object('mortgages', COALESCE(json_agg(json_build_object(
           'mortgageId',              m.mortgage_id,
           'customerId',              la.customer_id,
           'propertyType',            m.property_type,
           'propertyAddress',         m.property_address,
           'valuationDate',           m.valuation_date,
           'valuationAmount',         m.valuation_amount,
           'valuerRef',               m.valuer_ref,
           'chargeStatus',            m.charge_status,
           'cersaiRegistrationRef',   m.cersai_registration_ref,
           'legalVerificationStatus', m.legal_verification_status,
           'currencyCode',            la.currency_code,
           'valuationHistory',        COALESCE((SELECT json_agg(json_build_object(
                                          'valuationDate', h.valuation_date, 'valuationAmount', h.valuation_amount, 'valuerRef', h.valuer_ref)
                                          ORDER BY h.valuation_date)
                                        FROM mortgage_valuation_history h WHERE h.mortgage_id = m.mortgage_id), '[]'::json)
       ) ORDER BY m.mortgage_id), '[]'::json))
FROM mortgage m
JOIN loan_account la ON la.account_id = m.account_id
WHERE m.charge_status <> 'RELEASED';

\o
