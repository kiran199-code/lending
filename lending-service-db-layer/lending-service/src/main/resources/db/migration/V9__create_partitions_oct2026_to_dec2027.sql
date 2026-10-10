-- V9: V5/V6 only bootstrapped partitions for Jul-Sep 2026. Any accrual or EMI posted with a
-- business/value date after 30 Sep 2026 failed with "no partition of relation ... found for row"
-- (surfaced as HTTP 409). Create Oct 2026 .. Dec 2027 now; PartitionMaintenanceService keeps
-- creating future months at runtime (create_monthly_partition is idempotent).
DO $$
DECLARE
    m DATE := DATE '2026-10-01';
BEGIN
    WHILE m <= DATE '2027-12-01' LOOP
        PERFORM create_monthly_partition('interest_accrual', EXTRACT(YEAR FROM m)::INT, EXTRACT(MONTH FROM m)::INT);
        PERFORM create_monthly_partition('emi_transaction',  EXTRACT(YEAR FROM m)::INT, EXTRACT(MONTH FROM m)::INT);
        m := (m + INTERVAL '1 month')::DATE;
    END LOOP;
END $$;
