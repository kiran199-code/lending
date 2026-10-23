package com.bank.lending.chit.domain;

/**
 * Cycle lifecycle. See Lifecycles.CYCLE.
 */
public enum CycleStatus {
    SCHEDULED,
    COLLECTION_OPEN,
    AUCTION_OPEN,
    AUCTION_CLOSED,
    PAYOUT_PENDING,
    PAYOUT_DONE,
    SETTLED
}
