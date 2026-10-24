-- Phase 4: demands, payments, late fees and default detection.
-- No new tables: V1 already has chit_demand, chit_payment, chit_payment_allocation, chit_late_fee and chit_default_case.
-- This migration adds the columns and checks the collection code needs, and nothing else.

-- When collection opened for a cycle (the business date of the demand run), as against the cycle's due date.
ALTER TABLE chit_cycle ADD COLUMN collection_opened_on DATE;

-- The business date a demand was raised on. A run that catches up a missed day raises demands after their due date;
-- due_date stays the real due date (late fees count from it), raised_on records when the books learnt of it.
ALTER TABLE chit_demand ADD COLUMN raised_on DATE;
UPDATE chit_demand SET raised_on = due_date WHERE raised_on IS NULL;
ALTER TABLE chit_demand ALTER COLUMN raised_on SET NOT NULL;

-- A demand is PAID exactly when the subscription (net of the dividend adjustment) and every late fee accrued are paid.
-- The code sets the status; this is the database refusing a status that disagrees with the figures.
ALTER TABLE chit_demand ADD CONSTRAINT ck_demand_paid_settled CHECK (
    status <> 'PAID' OR (subscription_paid = subscription_amount - dividend_adjustment AND late_fee_paid = late_fee_accrued));

-- The daily jobs and the dues screens read "demands still open" far more often than they read paid ones.
CREATE INDEX idx_chit_demand_open ON chit_demand(due_date, ticket_id) WHERE status = 'OPEN';

-- The reference the payer or the bank gave (UPI reference, cheque no., bank UTR), and who reversed the payment.
ALTER TABLE chit_payment ADD COLUMN external_ref VARCHAR(60);
ALTER TABLE chit_payment ADD COLUMN reversed_by VARCHAR(60);
CREATE INDEX idx_chit_payment_group ON chit_payment(group_id, id DESC);

-- Advance money. A payment that exceeds what is owed leaves an ADVANCE row (demand_id NULL). When a later demand is
-- raised, the advance is used oldest first: the ADVANCE row's advance_used goes up and a CURRENT row marked
-- from_advance is written against the new demand. The ledger needs no entry for that move: the payment already
-- credited the subscriber's receivable account, and the new demand debits it.
ALTER TABLE chit_payment_allocation ADD COLUMN from_advance BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chit_payment_allocation ADD COLUMN advance_used NUMERIC(18,2) NOT NULL DEFAULT 0;
ALTER TABLE chit_payment_allocation ADD CONSTRAINT ck_alloc_advance_used_range CHECK (advance_used >= 0 AND advance_used <= amount);
ALTER TABLE chit_payment_allocation ADD CONSTRAINT ck_alloc_advance_used_only_advance CHECK (bucket = 'ADVANCE' OR advance_used = 0);
ALTER TABLE chit_payment_allocation ADD CONSTRAINT ck_alloc_from_advance_shape CHECK (
    NOT from_advance OR (demand_id IS NOT NULL AND bucket IN ('ARREARS','CURRENT')));
CREATE INDEX idx_chit_alloc_advance_open ON chit_payment_allocation(payment_id) WHERE bucket = 'ADVANCE' AND NOT reversed;

-- Why and on what figures a ticket was flagged, so the case can be explained later without recomputing history.
ALTER TABLE chit_default_case ADD COLUMN reason VARCHAR(200);
ALTER TABLE chit_default_case ADD COLUMN missed_instalments INTEGER;
ALTER TABLE chit_default_case ADD COLUMN days_overdue INTEGER;
ALTER TABLE chit_default_case ADD COLUMN opened_by VARCHAR(60);
