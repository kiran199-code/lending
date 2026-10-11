-- V11: more day-count conventions, more schedule types, and the schedule parameters that go with them.
--
-- Day counts : ACT/365, ACT/ACT, ACT/360, 30/360 (US), 30E/360
-- Schedules  : EMI, EQUAL_PRINCIPAL, INTEREST_ONLY, BALLOON, STEP_UP_EMI
-- Parameters : repayment frequency, moratorium (interest-only) months, balloon %, step-up % per year
--
-- Products hold the default and the allowed maximum; an account copies the values it was created
-- with. Existing rows get the neutral values (monthly, no moratorium, no balloon, no step-up), so
-- every existing schedule keeps meaning exactly what it meant before.

-- ---- widen the CHECK lists (inline checks created in V1 / V10 carry the default names) ----------
ALTER TABLE loan_product DROP CONSTRAINT IF EXISTS loan_product_day_count_convention_check;
ALTER TABLE loan_product DROP CONSTRAINT IF EXISTS loan_product_schedule_type_check;
ALTER TABLE loan_account DROP CONSTRAINT IF EXISTS loan_account_day_count_convention_check;
ALTER TABLE loan_account DROP CONSTRAINT IF EXISTS loan_account_schedule_type_check;

ALTER TABLE loan_product ADD CONSTRAINT chk_product_day_count
    CHECK (day_count_convention IN ('ACT/365','ACT/ACT','ACT/360','30/360','30E/360'));
ALTER TABLE loan_product ADD CONSTRAINT chk_product_schedule_type
    CHECK (schedule_type IN ('EMI','EQUAL_PRINCIPAL','INTEREST_ONLY','BALLOON','STEP_UP_EMI'));
ALTER TABLE loan_account ADD CONSTRAINT chk_account_day_count
    CHECK (day_count_convention IN ('ACT/365','ACT/ACT','ACT/360','30/360','30E/360'));
ALTER TABLE loan_account ADD CONSTRAINT chk_account_schedule_type
    CHECK (schedule_type IN ('EMI','EQUAL_PRINCIPAL','INTEREST_ONLY','BALLOON','STEP_UP_EMI'));

-- ---- product: defaults and maxima -------------------------------------------------------------
ALTER TABLE loan_product
    ADD COLUMN repayment_frequency          VARCHAR(15)  NOT NULL DEFAULT 'MONTHLY',
    ADD COLUMN allowed_repayment_frequencies VARCHAR(60) NOT NULL DEFAULT 'MONTHLY',
    ADD COLUMN moratorium_months            INT          NOT NULL DEFAULT 0,
    ADD COLUMN max_moratorium_months        INT,
    ADD COLUMN balloon_percent              NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN max_balloon_percent          NUMERIC(5,2),
    ADD COLUMN step_up_percent              NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN max_step_up_percent          NUMERIC(5,2);

ALTER TABLE loan_product
    ADD CONSTRAINT chk_product_frequency
        CHECK (repayment_frequency IN ('MONTHLY','QUARTERLY','HALF_YEARLY','YEARLY')),
    ADD CONSTRAINT chk_product_moratorium
        CHECK (moratorium_months >= 0 AND (max_moratorium_months IS NULL OR max_moratorium_months >= moratorium_months)),
    ADD CONSTRAINT chk_product_balloon
        CHECK (balloon_percent >= 0 AND balloon_percent < 100
               AND (max_balloon_percent IS NULL OR (max_balloon_percent >= balloon_percent AND max_balloon_percent < 100))),
    ADD CONSTRAINT chk_product_step_up
        CHECK (step_up_percent >= 0 AND step_up_percent <= 100
               AND (max_step_up_percent IS NULL OR (max_step_up_percent >= step_up_percent AND max_step_up_percent <= 100)));

-- ---- account: the values it was created with ---------------------------------------------------
ALTER TABLE loan_account
    ADD COLUMN repayment_frequency VARCHAR(15)  NOT NULL DEFAULT 'MONTHLY',
    ADD COLUMN moratorium_months   INT          NOT NULL DEFAULT 0,
    ADD COLUMN balloon_percent     NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN step_up_percent     NUMERIC(5,2) NOT NULL DEFAULT 0;

ALTER TABLE loan_account
    ADD CONSTRAINT chk_account_frequency
        CHECK (repayment_frequency IN ('MONTHLY','QUARTERLY','HALF_YEARLY','YEARLY')),
    ADD CONSTRAINT chk_account_moratorium CHECK (moratorium_months >= 0),
    ADD CONSTRAINT chk_account_balloon    CHECK (balloon_percent >= 0 AND balloon_percent < 100),
    ADD CONSTRAINT chk_account_step_up    CHECK (step_up_percent >= 0 AND step_up_percent <= 100);

-- ---- sample products for the new schedule types -----------------------------------------------
INSERT INTO loan_product
    (product_code, product_name, description, account_type, rate_type, schedule_type, day_count_convention,
     benchmark_rate_ref, default_interest_rate, default_spread,
     min_interest_rate, max_interest_rate, min_spread, max_spread,
     min_sanctioned_amount, max_sanctioned_amount, min_tenure_months, max_tenure_months,
     repayment_frequency, allowed_repayment_frequencies,
     moratorium_months, max_moratorium_months, balloon_percent, max_balloon_percent,
     step_up_percent, max_step_up_percent)
VALUES
    ('TL01', 'Term Loan - Equal Principal',
     'Fixed principal every instalment, so the instalment falls over time; interest on 30E/360',
     'UNSECURED', 'FIXED', 'EQUAL_PRINCIPAL', '30E/360',
     NULL, 11.0000, NULL, 9.0000, 16.0000, NULL, NULL, 100000, 20000000, 12, 120,
     'MONTHLY', 'MONTHLY,QUARTERLY,HALF_YEARLY', 0, 12, 0, NULL, 0, NULL),
    ('BB01', 'Bridge Loan - Interest Only',
     'Interest-only instalments with the whole principal repaid in the last one (bullet)',
     'UNSECURED', 'FIXED', 'INTEREST_ONLY', 'ACT/360',
     NULL, 12.0000, NULL, 10.0000, 18.0000, NULL, NULL, 500000, 50000000, 3, 36,
     'MONTHLY', 'MONTHLY,QUARTERLY', 0, 0, 0, NULL, 0, NULL),
    ('AB01', 'Auto Loan - Balloon',
     'Lower instalments with a balloon share of the amount due with the last instalment',
     'UNSECURED', 'FIXED', 'BALLOON', 'ACT/365',
     NULL, 9.5000, NULL, 8.0000, 13.0000, NULL, NULL, 100000, 5000000, 12, 84,
     'MONTHLY', 'MONTHLY', 0, 6, 30, 50, 0, NULL),
    ('SU01', 'Step-up Personal Loan',
     'Instalment grows by a set percentage each year; for borrowers whose income is expected to rise',
     'UNSECURED', 'FIXED', 'STEP_UP_EMI', 'ACT/365',
     NULL, 12.0000, NULL, 9.0000, 17.0000, NULL, NULL, 50000, 3000000, 12, 84,
     'MONTHLY', 'MONTHLY,QUARTERLY', 0, 6, 0, NULL, 5, 15),
    ('EM01', 'Education Loan - Study Moratorium',
     'Interest-only during the study period, then equated instalments',
     'UNSECURED', 'FIXED', 'EMI', '30/360',
     NULL, 9.5000, NULL, 8.0000, 13.0000, NULL, NULL, 100000, 5000000, 24, 144,
     'MONTHLY', 'MONTHLY,QUARTERLY', 12, 48, 0, NULL, 0, NULL)
ON CONFLICT (product_code) DO NOTHING;
