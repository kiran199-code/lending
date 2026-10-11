-- V10: Product master. A loan product defines the terms that are defaulted onto an account when
-- the product is selected at creation, and the bands (rate, spread, amount, tenure) that an
-- account's own values must stay within. Changing a product never rewrites existing accounts:
-- the values are copied onto the account at creation time.
-- NULL in a min/max column means "no limit".

CREATE TABLE loan_product (
    product_id               BIGSERIAL PRIMARY KEY,
    product_code             VARCHAR(10) NOT NULL UNIQUE,
    product_name             VARCHAR(100) NOT NULL,
    description              VARCHAR(500),
    account_type             VARCHAR(15) NOT NULL
                                CHECK (account_type IN ('UNSECURED','MORTGAGE')),
    rate_type                VARCHAR(10) NOT NULL
                                CHECK (rate_type IN ('FIXED','FLOATING')),
    schedule_type            VARCHAR(20) NOT NULL DEFAULT 'EMI'
                                CHECK (schedule_type IN ('EMI')),
    day_count_convention     VARCHAR(10) NOT NULL DEFAULT 'ACT/365'
                                CHECK (day_count_convention IN ('ACT/365','ACT/ACT','30/360')),
    benchmark_rate_ref       VARCHAR(20),
    default_interest_rate    NUMERIC(6,4) NOT NULL CHECK (default_interest_rate >= 0),
    default_spread           NUMERIC(6,4),
    min_interest_rate        NUMERIC(6,4) CHECK (min_interest_rate >= 0),
    max_interest_rate        NUMERIC(6,4),
    min_spread               NUMERIC(6,4),
    max_spread               NUMERIC(6,4),
    min_sanctioned_amount    NUMERIC(15,2) CHECK (min_sanctioned_amount > 0),
    max_sanctioned_amount    NUMERIC(15,2),
    min_tenure_months        INT CHECK (min_tenure_months > 0),
    max_tenure_months        INT,
    status                   VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                                CHECK (status IN ('ACTIVE','INACTIVE')),
    version                  INT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_product_floating_benchmark
        CHECK (rate_type = 'FIXED' OR benchmark_rate_ref IS NOT NULL),
    CONSTRAINT chk_product_rate_band
        CHECK (min_interest_rate IS NULL OR max_interest_rate IS NULL OR min_interest_rate <= max_interest_rate),
    CONSTRAINT chk_product_spread_band
        CHECK (min_spread IS NULL OR max_spread IS NULL OR min_spread <= max_spread),
    CONSTRAINT chk_product_amount_band
        CHECK (min_sanctioned_amount IS NULL OR max_sanctioned_amount IS NULL
               OR min_sanctioned_amount <= max_sanctioned_amount),
    CONSTRAINT chk_product_tenure_band
        CHECK (min_tenure_months IS NULL OR max_tenure_months IS NULL
               OR min_tenure_months <= max_tenure_months)
);

CREATE INDEX idx_loan_product_status ON loan_product(status);

CREATE TRIGGER trg_loan_product_updated_at
    BEFORE UPDATE ON loan_product
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Standard products.
INSERT INTO loan_product
    (product_code, product_name, description, account_type, rate_type, day_count_convention,
     benchmark_rate_ref, default_interest_rate, default_spread,
     min_interest_rate, max_interest_rate, min_spread, max_spread,
     min_sanctioned_amount, max_sanctioned_amount, min_tenure_months, max_tenure_months)
VALUES
    ('PL01', 'Personal Loan', 'Unsecured fixed-rate personal loan', 'UNSECURED', 'FIXED', 'ACT/365',
     NULL, 10.5000, NULL, 8.0000, 17.0000, NULL, NULL, 50000, 5000000, 6, 84),
    ('BL01', 'Business Loan', 'Unsecured fixed-rate business loan', 'UNSECURED', 'FIXED', 'ACT/365',
     NULL, 11.5000, NULL, 9.0000, 17.0000, NULL, NULL, 100000, 10000000, 12, 120),
    ('EL01', 'Education Loan', 'Unsecured fixed-rate education loan', 'UNSECURED', 'FIXED', 'ACT/365',
     NULL, 9.5000, NULL, 8.0000, 13.0000, NULL, NULL, 100000, 5000000, 12, 120),
    ('GL01', 'Gold Loan', 'Short-tenure fixed-rate loan against gold', 'UNSECURED', 'FIXED', 'ACT/365',
     NULL, 9.0000, NULL, 7.0000, 13.0000, NULL, NULL, 10000, 1000000, 3, 36),
    ('AL01', 'Auto Loan', 'Unsecured floating-rate vehicle loan linked to REPO', 'UNSECURED', 'FLOATING', 'ACT/365',
     'REPO', 9.7500, 3.0000, 8.0000, 13.0000, 1.0000, 5.0000, 100000, 5000000, 12, 84),
    ('HL01', 'Home Loan', 'Floating-rate home loan secured by property, linked to REPO', 'MORTGAGE', 'FLOATING', 'ACT/365',
     'REPO', 8.7500, 2.5000, 8.0000, 13.0000, 1.0000, 5.0000, 500000, 50000000, 60, 360)
ON CONFLICT (product_code) DO NOTHING;

-- Legacy product codes already used by accounts but not in the standard set above: create an open
-- (no bands) product for each so every existing account can be linked.
INSERT INTO loan_product
    (product_code, product_name, description, account_type, rate_type, day_count_convention,
     benchmark_rate_ref, default_interest_rate, default_spread)
SELECT a.product_code,
       'Legacy product ' || a.product_code,
       'Created by migration V10 from accounts that already used this code; no limits set',
       mode() WITHIN GROUP (ORDER BY a.account_type),
       mode() WITHIN GROUP (ORDER BY a.rate_type),
       mode() WITHIN GROUP (ORDER BY a.day_count_convention),
       CASE WHEN mode() WITHIN GROUP (ORDER BY a.rate_type) = 'FLOATING'
            THEN COALESCE(mode() WITHIN GROUP (ORDER BY a.benchmark_rate_ref), 'REPO') END,
       round(avg(a.interest_rate), 4),
       CASE WHEN mode() WITHIN GROUP (ORDER BY a.rate_type) = 'FLOATING'
            THEN round(COALESCE(avg(a.spread), 0), 4) END
FROM loan_account a
WHERE NOT EXISTS (SELECT 1 FROM loan_product p WHERE p.product_code = a.product_code)
GROUP BY a.product_code;

-- Link accounts to their product.
ALTER TABLE loan_account ADD COLUMN product_id BIGINT;
ALTER TABLE loan_account ADD COLUMN schedule_type VARCHAR(20) NOT NULL DEFAULT 'EMI'
    CHECK (schedule_type IN ('EMI'));

UPDATE loan_account a
SET product_id = p.product_id
FROM loan_product p
WHERE p.product_code = a.product_code;

ALTER TABLE loan_account ALTER COLUMN product_id SET NOT NULL;
ALTER TABLE loan_account
    ADD CONSTRAINT fk_loan_account_product FOREIGN KEY (product_id) REFERENCES loan_product(product_id);
CREATE INDEX idx_loan_account_product_id ON loan_account(product_id);
