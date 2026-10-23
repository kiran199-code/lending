package com.bank.lending.chit.domain;

/**
 * Who wins when two or more bids have the same discount.
 */
public enum TieBreakRule {
    EARLIEST_BID_TIME,
    LOTTERY,
    LOWEST_TICKET_NO
}
