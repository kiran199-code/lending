package com.bank.lending.admin.common;

/** A call to another service failed; carries that service's HTTP status and message back to the UI. */
public class UpstreamException extends RuntimeException {
    private final int status;

    public UpstreamException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
