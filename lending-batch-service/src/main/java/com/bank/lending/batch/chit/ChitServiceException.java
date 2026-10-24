package com.bank.lending.batch.chit;

/** A call to the chit service failed (non-2xx after retries, or it was unreachable). */
public class ChitServiceException extends IllegalStateException {

    private final int httpStatus;

    public ChitServiceException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /** HTTP status of the failed response, or 0 when no response was received (I/O error, timeout). */
    public int httpStatus() {
        return httpStatus;
    }
}
