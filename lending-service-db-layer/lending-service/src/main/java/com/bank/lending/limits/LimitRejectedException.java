package com.bank.lending.limits;

/** LCM answered and said no (limit exceeded, facility not active, unknown reservation...). {@code code} is LCM's stable error code. */
public class LimitRejectedException extends RuntimeException {
    private final String code;
    private final int httpStatus;

    public LimitRejectedException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
}
