package com.bank.lending.chit.domain;

/**
 * What happens when an auction closes with no bid.
 * LOTTERY draws a winner at the minimum discount; FOREMAN_DEFAULT_DISCOUNT draws a winner at the foreman's default discount;
 * ROLL_OVER_CYCLE leaves the cycle open and extends the calendar. TODO-VERIFY: the real-world variants differ.
 */
public enum NoBidRule {
    LOTTERY,
    FOREMAN_DEFAULT_DISCOUNT,
    ROLL_OVER_CYCLE
}
