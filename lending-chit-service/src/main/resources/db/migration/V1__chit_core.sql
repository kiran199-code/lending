-- V1: chit groups, rules versions, tickets, cycles, demands, payments, auctions, payouts, dividends, security,
-- defaults, transfers and the closure report. Runs in schema 'chit' (Flyway default schema), so names are unqualified.
-- Conventions: NUMERIC(18,2) for money, a version column for optimistic locking, created/updated audit columns,
-- and CHECK / UNIQUE constraints that enforce the invariants (one winner per cycle, one prize per ticket,
-- non-negative amounts, maker is not checker).

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE chit_group (
    id                  BIGSERIAL PRIMARY KEY,
    group_code          VARCHAR(30)  NOT NULL UNIQUE,
    name                VARCHAR(120) NOT NULL,
    foreman_ref         VARCHAR(60)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                          CHECK (status IN ('DRAFT','OPEN_FOR_ENROLMENT','FULL','ACTIVE','CLOSING','CLOSED','CANCELLED')),
    chit_value          NUMERIC(18,2) NOT NULL CHECK (chit_value > 0),
    ticket_count        INTEGER       NOT NULL CHECK (ticket_count >= 2),
    cycle_count         INTEGER       NOT NULL,
    subscription        NUMERIC(18,2) NOT NULL CHECK (subscription > 0),
    frequency           VARCHAR(12)   NOT NULL DEFAULT 'MONTHLY' CHECK (frequency IN ('MONTHLY','WEEKLY','FORTNIGHTLY')),
    start_date          DATE          NOT NULL,
    current_rules_version INTEGER     NOT NULL DEFAULT 1,
    activated_at        TIMESTAMPTZ,
    closed_at           TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(60)   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by          VARCHAR(60),
    -- a standard chit: every ticket wins exactly once, so cycles = tickets, and the subscriptions add up to the pot
    CONSTRAINT ck_group_cycles_eq_tickets CHECK (cycle_count = ticket_count),
    CONSTRAINT ck_group_subscription_sum CHECK (subscription * ticket_count = chit_value)
);
CREATE TRIGGER trg_chit_group_updated_at BEFORE UPDATE ON chit_group FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Versioned rules. rules_json is the ChitRules snapshot. Once frozen (at activation) a row can never change.
CREATE TABLE chit_group_rules_version (
    id          BIGSERIAL PRIMARY KEY,
    group_id    BIGINT      NOT NULL REFERENCES chit_group(id),
    version_no  INTEGER     NOT NULL CHECK (version_no >= 1),
    rules_json  TEXT        NOT NULL,
    frozen      BOOLEAN     NOT NULL DEFAULT FALSE,
    frozen_at   TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  VARCHAR(60) NOT NULL,
    UNIQUE (group_id, version_no)
);
CREATE OR REPLACE FUNCTION chit_rules_frozen_guard() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.frozen THEN
        RAISE EXCEPTION 'Rules version % of group % is frozen and cannot be changed or deleted', OLD.version_no, OLD.group_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_chit_rules_frozen BEFORE UPDATE OR DELETE ON chit_group_rules_version
    FOR EACH ROW EXECUTE FUNCTION chit_rules_frozen_guard();

CREATE TABLE chit_ticket (
    id                 BIGSERIAL PRIMARY KEY,
    group_id           BIGINT      NOT NULL REFERENCES chit_group(id),
    ticket_no          INTEGER     NOT NULL CHECK (ticket_no >= 1),
    customer_id        BIGINT      NOT NULL,
    status             VARCHAR(12) NOT NULL DEFAULT 'ENROLLED'
                         CHECK (status IN ('ENROLLED','ACTIVE','PRIZED','COMPLETED','DEFAULTED','TRANSFERRED','EXITED')),
    prized_cycle_no    INTEGER,
    payout_account_ref VARCHAR(60),
    version            BIGINT      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         VARCHAR(60) NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by         VARCHAR(60),
    UNIQUE (group_id, ticket_no)
);
CREATE INDEX idx_chit_ticket_customer ON chit_ticket(customer_id);
CREATE TRIGGER trg_chit_ticket_updated_at BEFORE UPDATE ON chit_ticket FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Who holds a ticket and since when. History is kept; exactly one open row (valid_to IS NULL) per ticket.
CREATE TABLE chit_subscriber_link (
    id          BIGSERIAL PRIMARY KEY,
    ticket_id   BIGINT      NOT NULL REFERENCES chit_ticket(id),
    customer_id BIGINT      NOT NULL,
    valid_from  DATE        NOT NULL,
    valid_to    DATE,
    reason      VARCHAR(20) NOT NULL DEFAULT 'ENROLMENT' CHECK (reason IN ('ENROLMENT','TRANSFER','REPLACEMENT')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  VARCHAR(60) NOT NULL,
    CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE UNIQUE INDEX uq_chit_subscriber_open ON chit_subscriber_link(ticket_id) WHERE valid_to IS NULL;

CREATE TABLE chit_cycle (
    id                  BIGSERIAL PRIMARY KEY,
    group_id            BIGINT      NOT NULL REFERENCES chit_group(id),
    cycle_no            INTEGER     NOT NULL CHECK (cycle_no >= 1),
    status              VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED'
                          CHECK (status IN ('SCHEDULED','COLLECTION_OPEN','AUCTION_OPEN','AUCTION_CLOSED','PAYOUT_PENDING','PAYOUT_DONE','SETTLED')),
    due_date            DATE        NOT NULL,
    auction_date        DATE        NOT NULL,
    winner_ticket_id    BIGINT      REFERENCES chit_ticket(id),
    discount_amount     NUMERIC(18,2) CHECK (discount_amount >= 0),
    prize_amount        NUMERIC(18,2) CHECK (prize_amount >= 0),
    commission_amount   NUMERIC(18,2) CHECK (commission_amount >= 0),
    dividend_pool       NUMERIC(18,2) CHECK (dividend_pool >= 0),
    settled_at          TIMESTAMPTZ,
    version             BIGINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (group_id, cycle_no),
    CHECK (auction_date >= due_date)
);
-- one prize per ticket: a ticket can be the winner of at most one cycle (tickets belong to one group)
CREATE UNIQUE INDEX uq_chit_cycle_winner ON chit_cycle(winner_ticket_id) WHERE winner_ticket_id IS NOT NULL;
CREATE TRIGGER trg_chit_cycle_updated_at BEFORE UPDATE ON chit_cycle FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE chit_demand (
    id                    BIGSERIAL PRIMARY KEY,
    demand_ref            VARCHAR(40)   NOT NULL UNIQUE,
    cycle_id              BIGINT        NOT NULL REFERENCES chit_cycle(id),
    ticket_id             BIGINT        NOT NULL REFERENCES chit_ticket(id),
    due_date              DATE          NOT NULL,
    subscription_amount   NUMERIC(18,2) NOT NULL CHECK (subscription_amount > 0),
    dividend_adjustment   NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (dividend_adjustment >= 0),
    subscription_paid     NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (subscription_paid >= 0),
    late_fee_accrued      NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (late_fee_accrued >= 0),
    late_fee_paid         NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (late_fee_paid >= 0),
    status                VARCHAR(8)    NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','PAID','CANCELLED')),
    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (cycle_id, ticket_id),
    -- what is due net of the dividend adjustment can never be overpaid
    CONSTRAINT ck_demand_not_overpaid CHECK (subscription_paid <= subscription_amount - dividend_adjustment),
    CONSTRAINT ck_demand_fee_not_overpaid CHECK (late_fee_paid <= late_fee_accrued),
    CONSTRAINT ck_demand_adjustment_le_amount CHECK (dividend_adjustment <= subscription_amount)
);
CREATE INDEX idx_chit_demand_ticket ON chit_demand(ticket_id);
CREATE TRIGGER trg_chit_demand_updated_at BEFORE UPDATE ON chit_demand FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE chit_payment (
    id              BIGSERIAL PRIMARY KEY,
    payment_ref     VARCHAR(40)   NOT NULL UNIQUE,
    group_id        BIGINT        NOT NULL REFERENCES chit_group(id),
    ticket_id       BIGINT        NOT NULL REFERENCES chit_ticket(id),
    amount          NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    channel         VARCHAR(20)   NOT NULL CHECK (channel IN ('UPI','BANK_TRANSFER','CASH_AT_FOREMAN','AUTO_DEBIT')),
    status          VARCHAR(10)   NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','REVERSED')),
    business_date   DATE          NOT NULL,
    idempotency_key VARCHAR(100)  NOT NULL UNIQUE,
    reversal_reason VARCHAR(120),
    reversed_at     TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      VARCHAR(60)   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK ((status = 'REVERSED') = (reversed_at IS NOT NULL))
);
CREATE INDEX idx_chit_payment_ticket ON chit_payment(ticket_id);
CREATE TRIGGER trg_chit_payment_updated_at BEFORE UPDATE ON chit_payment FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Where each payment went. A reversal marks its rows reversed (they are never deleted).
CREATE TABLE chit_payment_allocation (
    id          BIGSERIAL PRIMARY KEY,
    payment_id  BIGINT        NOT NULL REFERENCES chit_payment(id),
    demand_id   BIGINT        REFERENCES chit_demand(id),
    bucket      VARCHAR(10)   NOT NULL CHECK (bucket IN ('LATE_FEES','ARREARS','CURRENT','ADVANCE')),
    amount      NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    reversed    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK ((bucket = 'ADVANCE') = (demand_id IS NULL))
);
CREATE INDEX idx_chit_alloc_payment ON chit_payment_allocation(payment_id);
CREATE INDEX idx_chit_alloc_demand ON chit_payment_allocation(demand_id);

-- One row per demand per day: a rerun or a catch-up can never charge a day twice.
CREATE TABLE chit_late_fee (
    id         BIGSERIAL PRIMARY KEY,
    demand_id  BIGINT        NOT NULL REFERENCES chit_demand(id),
    fee_date   DATE          NOT NULL,
    amount     NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (demand_id, fee_date)
);

CREATE TABLE chit_auction (
    id               BIGSERIAL PRIMARY KEY,
    cycle_id         BIGINT      NOT NULL UNIQUE REFERENCES chit_cycle(id),
    auction_type     VARCHAR(30) NOT NULL CHECK (auction_type IN ('OPEN_ASCENDING_DISCOUNT','SEALED_BID','LOTTERY','FIXED_ORDER')),
    status           VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED','NO_BID','ROLLED_OVER')),
    opened_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    opened_by        VARCHAR(60) NOT NULL,
    closed_at        TIMESTAMPTZ,
    closed_by        VARCHAR(60),
    winning_bid_id   BIGINT,
    version          BIGINT      NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status = 'OPEN') = (closed_at IS NULL))
);
CREATE TRIGGER trg_chit_auction_updated_at BEFORE UPDATE ON chit_auction FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE chit_bid (
    id              BIGSERIAL PRIMARY KEY,
    auction_id      BIGINT        NOT NULL REFERENCES chit_auction(id),
    ticket_id       BIGINT        NOT NULL REFERENCES chit_ticket(id),
    bid_seq         INTEGER       NOT NULL CHECK (bid_seq >= 1),
    discount_amount NUMERIC(18,2) NOT NULL CHECK (discount_amount > 0),
    bid_time        TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    placed_by       VARCHAR(60)   NOT NULL,
    status          VARCHAR(10)   NOT NULL DEFAULT 'VALID' CHECK (status IN ('VALID','WITHDRAWN')),
    UNIQUE (auction_id, bid_seq)
);
ALTER TABLE chit_auction ADD CONSTRAINT fk_chit_auction_winning_bid FOREIGN KEY (winning_bid_id) REFERENCES chit_bid(id);

-- A draw that can be replayed: algorithm, seed, candidates and result are stored (see calc/Draw).
CREATE TABLE chit_draw_record (
    id         BIGSERIAL PRIMARY KEY,
    group_id   BIGINT      NOT NULL REFERENCES chit_group(id),
    cycle_id   BIGINT      REFERENCES chit_cycle(id),
    purpose    VARCHAR(20) NOT NULL CHECK (purpose IN ('TIE_BREAK','NO_BID','LOTTERY_AUCTION','FIXED_ORDER')),
    algorithm  VARCHAR(30) NOT NULL,
    seed       BIGINT      NOT NULL,
    candidates TEXT        NOT NULL,
    result     TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(60) NOT NULL
);

CREATE TABLE chit_prize_payout (
    id            BIGSERIAL PRIMARY KEY,
    cycle_id      BIGINT        NOT NULL UNIQUE REFERENCES chit_cycle(id),   -- one payout per cycle
    ticket_id     BIGINT        NOT NULL REFERENCES chit_ticket(id),
    gross_prize   NUMERIC(18,2) NOT NULL CHECK (gross_prize >= 0),
    hold_amount   NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (hold_amount >= 0),
    net_amount    NUMERIC(18,2) NOT NULL CHECK (net_amount >= 0),
    status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING_APPROVAL'
                    CHECK (status IN ('PENDING_APPROVAL','APPROVED','REJECTED','DISBURSED')),
    made_by       VARCHAR(60)   NOT NULL,
    checked_by    VARCHAR(60),
    payout_ref    VARCHAR(40),
    disbursed_at  TIMESTAMPTZ,
    version       BIGINT        NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_payout_net CHECK (net_amount + hold_amount = gross_prize),
    CONSTRAINT ck_payout_maker_checker CHECK (checked_by IS NULL OR checked_by <> made_by),
    CONSTRAINT ck_payout_checked CHECK (status = 'PENDING_APPROVAL' OR checked_by IS NOT NULL),
    CONSTRAINT ck_payout_disbursed CHECK ((status = 'DISBURSED') = (disbursed_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_chit_payout_ticket ON chit_prize_payout(ticket_id) WHERE status <> 'REJECTED'; -- one prize per ticket
CREATE TRIGGER trg_chit_payout_updated_at BEFORE UPDATE ON chit_prize_payout FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE chit_dividend (
    id                   BIGSERIAL PRIMARY KEY,
    cycle_id             BIGINT        NOT NULL REFERENCES chit_cycle(id),
    ticket_id            BIGINT        NOT NULL REFERENCES chit_ticket(id),
    amount               NUMERIC(18,2) NOT NULL CHECK (amount >= 0),
    mode                 VARCHAR(30)   NOT NULL CHECK (mode IN ('ADJUST_NEXT_INSTALMENT','PAY_OUT','CREDIT_SUBSCRIBER_BALANCE')),
    status               VARCHAR(10)   NOT NULL DEFAULT 'DECLARED' CHECK (status IN ('DECLARED','APPLIED','PAID','FORFEITED')),
    applied_to_demand_id BIGINT        REFERENCES chit_demand(id),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (cycle_id, ticket_id)
);
CREATE TRIGGER trg_chit_dividend_updated_at BEFORE UPDATE ON chit_dividend FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE chit_security (
    id                    BIGSERIAL PRIMARY KEY,
    ticket_id             BIGINT        NOT NULL REFERENCES chit_ticket(id),
    kind                  VARCHAR(10)   NOT NULL CHECK (kind IN ('DEPOSIT','GUARANTOR','LIEN')),
    amount                NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (amount >= 0),
    guarantor_customer_id BIGINT,
    status                VARCHAR(10)   NOT NULL DEFAULT 'HELD' CHECK (status IN ('HELD','VERIFIED','RELEASED','APPLIED','RETURNED')),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            VARCHAR(60)   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (kind <> 'GUARANTOR' OR guarantor_customer_id IS NOT NULL),
    CHECK (kind = 'GUARANTOR' OR amount > 0)
);
CREATE INDEX idx_chit_security_ticket ON chit_security(ticket_id);
CREATE TRIGGER trg_chit_security_updated_at BEFORE UPDATE ON chit_security FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE chit_default_case (
    id                    BIGSERIAL PRIMARY KEY,
    ticket_id             BIGINT        NOT NULL REFERENCES chit_ticket(id),
    opened_on             DATE          NOT NULL,
    status                VARCHAR(12)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CURED','RECOVERED','WRITTEN_OFF')),
    shortfall_amount      NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (shortfall_amount >= 0),
    foreman_covered       NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (foreman_covered >= 0),
    recovered_amount      NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (recovered_amount >= 0),
    written_off_amount    NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (written_off_amount >= 0),
    writeoff_made_by      VARCHAR(60),
    writeoff_checked_by   VARCHAR(60),
    closed_on             DATE,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_default_writeoff_mc CHECK (writeoff_checked_by IS NULL OR writeoff_checked_by <> writeoff_made_by),
    CONSTRAINT ck_default_writeoff_needs_approval CHECK (written_off_amount = 0 OR writeoff_checked_by IS NOT NULL)
);
-- one open case per ticket
CREATE UNIQUE INDEX uq_chit_default_open ON chit_default_case(ticket_id) WHERE status = 'OPEN';
CREATE TRIGGER trg_chit_default_updated_at BEFORE UPDATE ON chit_default_case FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE chit_ticket_transfer (
    id               BIGSERIAL PRIMARY KEY,
    ticket_id        BIGINT        NOT NULL REFERENCES chit_ticket(id),
    from_customer_id BIGINT        NOT NULL,
    to_customer_id   BIGINT        NOT NULL,
    fee_amount       NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    status           VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    made_by          VARCHAR(60)   NOT NULL,
    checked_by       VARCHAR(60),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_at       TIMESTAMPTZ,
    CHECK (from_customer_id <> to_customer_id),
    CONSTRAINT ck_transfer_maker_checker CHECK (checked_by IS NULL OR checked_by <> made_by),
    CONSTRAINT ck_transfer_decided CHECK (status = 'PENDING' OR checked_by IS NOT NULL)
);
CREATE UNIQUE INDEX uq_chit_transfer_pending ON chit_ticket_transfer(ticket_id) WHERE status = 'PENDING';

CREATE TABLE chit_closure_report (
    id           BIGSERIAL PRIMARY KEY,
    group_id     BIGINT      NOT NULL UNIQUE REFERENCES chit_group(id),
    report_json  TEXT        NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    generated_by VARCHAR(60) NOT NULL
);
