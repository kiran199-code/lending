-- V13: System business date and working-day calendar.
--
-- business_date_state holds the ONE current business date for the whole platform (id is pinned to 1).
-- All end-of-day processing, value-date checks, DPD and statements work off this date, not the
-- wall clock. It moves only through the API: OPEN -> (start EOD) EOD_RUNNING -> (advance) OPEN on
-- the next day. Seeded with today's date in India time; an admin can move it forward with the
-- "set" endpoint before the first EOD (see BusinessDateService).
CREATE TABLE business_date_state (
    id              SMALLINT     PRIMARY KEY CHECK (id = 1),
    business_date   DATE         NOT NULL,
    previous_date   DATE,
    status          VARCHAR(12)  NOT NULL CHECK (status IN ('OPEN', 'EOD_RUNNING')),
    updated_by      VARCHAR(100) NOT NULL DEFAULT 'system',
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         INT          NOT NULL DEFAULT 0
);

INSERT INTO business_date_state (id, business_date, previous_date, status)
VALUES (1, (now() AT TIME ZONE 'Asia/Kolkata')::date, NULL, 'OPEN');

-- One row per business date that has been opened: when it opened, when its EOD started and ended.
CREATE TABLE business_date_history (
    business_date   DATE         PRIMARY KEY,
    opened_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    eod_started_at  TIMESTAMPTZ,
    closed_at       TIMESTAMPTZ,
    closed_by       VARCHAR(100),
    note            VARCHAR(200)
);

INSERT INTO business_date_history (business_date, note)
SELECT business_date, 'Initial business date' FROM business_date_state;

-- Bank holidays. Weekends are configured in application.yml (business-date.weekend-days), so only
-- the dates that are not already a weekend need to be listed here.
CREATE TABLE non_working_day (
    holiday_date    DATE         PRIMARY KEY,
    description     VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
