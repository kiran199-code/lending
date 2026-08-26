-- V3: Now that mortgage exists, add the FK from loan_account.mortgage_id -> mortgage.mortgage_id
-- Completes the circular reference started in V1.

ALTER TABLE loan_account
    ADD CONSTRAINT fk_loan_account_mortgage
    FOREIGN KEY (mortgage_id) REFERENCES mortgage(mortgage_id);

CREATE INDEX idx_loan_account_mortgage ON loan_account(mortgage_id);
