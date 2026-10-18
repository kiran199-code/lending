-- V3: breaches raised by the monitoring sweep.

CREATE TABLE breach (
    id            BIGSERIAL PRIMARY KEY,
    breach_type   VARCHAR(30) NOT NULL CHECK (breach_type IN
                    ('OVER_LIMIT','DRAWING_POWER_SHORTFALL','EXPIRED_WITH_UTILISATION',
                     'VALUATION_STALE','INSURANCE_EXPIRED')),
    severity      VARCHAR(8) NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH')),
    facility_id   BIGINT REFERENCES facility(id),
    collateral_id BIGINT REFERENCES collateral(id),
    detail        TEXT NOT NULL,
    status        VARCHAR(12) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACKNOWLEDGED','CLOSED')),
    acknowledged_by VARCHAR(60),
    detected_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at     TIMESTAMPTZ,
    CHECK ((facility_id IS NOT NULL) <> (collateral_id IS NOT NULL))
);
-- One live breach per subject and type.
CREATE UNIQUE INDEX uq_breach_facility_live ON breach(facility_id, breach_type)
    WHERE facility_id IS NOT NULL AND status <> 'CLOSED';
CREATE UNIQUE INDEX uq_breach_collateral_live ON breach(collateral_id, breach_type)
    WHERE collateral_id IS NOT NULL AND status <> 'CLOSED';
CREATE INDEX idx_breach_status ON breach(status, detected_at);
