package com.bank.lending.account.dto;

/** Upper bound on items per bulk call, so one request stays well inside typical HTTP timeouts. */
public final class BulkLimits {

    public static final int MAX_ITEMS = 200;

    private BulkLimits() {
    }
}
