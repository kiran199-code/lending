-- V20: collateral is held only in the Limits & Collateral Management (LCM) service.
--
-- The mortgage endpoints of lending-service (/api/v1/mortgages) are gone and nothing in the application writes to these
-- tables any more. Existing rows were moved to LCM as DRAFT collateral by lending-lcm-service/scripts/bridge_from_lending.sql
-- (reference MORT-<mortgage_id>); they are activated and allocated to the loan's facility there.
--
-- The tables, loan_account.mortgage_id and the constraints around them stay for one more release so that nothing is lost
-- and the move can be re-checked. A later migration drops them. Nothing else changes here, so it is safe to apply on its own.
COMMENT ON TABLE mortgage IS 'RETIRED in V20: collateral now lives in LCM (collateral MORT-<mortgage_id>). Read only; dropped in a later release.';
COMMENT ON TABLE mortgage_valuation_history IS 'RETIRED in V20: valuations now live in LCM. Read only; dropped in a later release.';
COMMENT ON COLUMN loan_account.mortgage_id IS 'RETIRED in V20: no longer set or read. A secured loan is covered by LCM collateral allocated to limit_facility_ref.';
