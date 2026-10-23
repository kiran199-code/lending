package com.bank.lending.chit.domain;

/**
 * The last cycle has no auction and no discount, so there is nothing to take the commission from.
 * NONE: the foreman earns no commission in the last cycle. DEDUCT_FROM_PRIZE: commission is deducted from the prize. TODO-VERIFY.
 */
public enum LastCycleCommissionRule {
    NONE,
    DEDUCT_FROM_PRIZE
}
