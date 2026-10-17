package com.bank.lending.accounting.model;

/** A journal could not be generated. {@link #reason()} is the machine-readable cause (see the API error codes). */
public class PostingException extends RuntimeException {

    public enum Reason {
        INACTIVE_EVENT, UNKNOWN_EVENT, NO_TEMPLATE, ROUTE_MISSING, ACCOUNT_BLOCKED, UNBALANCED, PERIOD_CLOSED,
        RATE_MISSING, INVALID_REQUEST, NEGATIVE_AMOUNT, EXPRESSION_ERROR, CURRENCY_MISMATCH, NO_LEDGER
    }

    private final Reason reason;

    public PostingException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public PostingException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
