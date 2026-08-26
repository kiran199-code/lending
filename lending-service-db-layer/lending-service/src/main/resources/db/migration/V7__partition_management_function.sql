-- V7: Reusable function to create next month's partition for any of the
-- partitioned tables. Called by a scheduled Spring @Scheduled job (monthly,
-- well ahead of month-end) rather than relying on manual migrations forever.
-- This keeps partition creation as a routine operational task, not a deploy.

CREATE OR REPLACE FUNCTION create_monthly_partition(
    p_table_name TEXT,
    p_year INT,
    p_month INT
) RETURNS VOID AS $$
DECLARE
    partition_name TEXT;
    start_date DATE;
    end_date DATE;
BEGIN
    start_date := make_date(p_year, p_month, 1);
    end_date := start_date + INTERVAL '1 month';
    partition_name := format('%s_%s_%s', p_table_name, p_year, lpad(p_month::TEXT, 2, '0'));

    IF NOT EXISTS (
        SELECT 1 FROM pg_class WHERE relname = partition_name
    ) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
            partition_name, p_table_name, start_date, end_date
        );
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Example manual invocation (the Spring scheduled job does this programmatically):
-- SELECT create_monthly_partition('interest_accrual', 2026, 10);
-- SELECT create_monthly_partition('emi_transaction', 2026, 10);
