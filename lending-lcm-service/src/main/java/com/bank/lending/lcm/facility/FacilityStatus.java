package com.bank.lending.lcm.facility;

public enum FacilityStatus {
    DRAFT, ACTIVE, SUSPENDED, FROZEN, EXPIRED, CLOSED;

    /** Only an ACTIVE facility accepts new drawdowns. Repayments and releases are allowed in every state but DRAFT. */
    public boolean allowsDrawdown() {
        return this == ACTIVE;
    }
}
