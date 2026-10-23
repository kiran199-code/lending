package com.bank.lending.chit.domain;

/**
 * Who carries a shortfall so the winner is paid on time.
 */
public enum ForemanGuaranteeMode {
    NONE,
    COVER_PAYOUT_RECOVER_FROM_TICKET,
    COVER_PAYOUT_FOREMAN_BEARS_LOSS
}
