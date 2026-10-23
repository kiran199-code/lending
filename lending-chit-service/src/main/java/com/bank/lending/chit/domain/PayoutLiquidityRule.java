package com.bank.lending.chit.domain;

/**
 * What to do when the pool holds less cash than the prize that is due.
 */
public enum PayoutLiquidityRule {
    BLOCK_UNTIL_FUNDED,
    FOREMAN_FLOATS,
    PAY_AVAILABLE_AND_BALANCE_LATER
}
