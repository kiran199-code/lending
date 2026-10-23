package com.bank.lending.chit.domain;

/**
 * Chit group lifecycle. See Lifecycles.GROUP.
 */
public enum GroupStatus {
    DRAFT,
    OPEN_FOR_ENROLMENT,
    FULL,
    ACTIVE,
    CLOSING,
    CLOSED,
    CANCELLED
}
