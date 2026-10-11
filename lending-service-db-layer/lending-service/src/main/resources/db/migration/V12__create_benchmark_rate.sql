-- V12: Published benchmark rates (MCLR, T-bill, repo, MIBOR, ...) used by floating-rate resets.
-- One row per (benchmark_ref, effective_date). A reset for business date D uses the most recent
-- rate with effective_date <= D, so a rate stays in force until the next one is published.
-- Rates are expressed in the same unit as loan_account.interest_rate (percent per annum).
CREATE TABLE benchmark_rate (
    rate_id         BIGSERIAL PRIMARY KEY,
    benchmark_ref   VARCHAR(20)   NOT NULL,
    effective_date  DATE          NOT NULL,
    rate            NUMERIC(6,4)  NOT NULL CHECK (rate >= 0),
    source          VARCHAR(50),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (benchmark_ref, effective_date)
);

CREATE INDEX idx_benchmark_rate_lookup ON benchmark_rate (benchmark_ref, effective_date DESC);
