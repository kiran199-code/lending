package com.bank.lending.limits;

/** LCM could not be reached or answered with a server error / rejected our credential. Safe to retry with the same idempotency key. */
public class LimitUnavailableException extends RuntimeException {
    public LimitUnavailableException(String message) {
        super(message);
    }

    public LimitUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
