package com.bank.lending.chit.domain;

/**
 * Security required from a prized subscriber.
 */
public enum SecurityRule {
    NONE,
    GUARANTOR,
    DEPOSIT,
    LIEN_ON_DEPOSIT
}
