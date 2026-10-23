package com.bank.lending.chit.domain;

/**
 * How the winner of a cycle is chosen.
 */
public enum AuctionType {
    OPEN_ASCENDING_DISCOUNT,
    SEALED_BID,
    LOTTERY,
    FIXED_ORDER
}
