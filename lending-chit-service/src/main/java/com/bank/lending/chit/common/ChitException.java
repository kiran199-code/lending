package com.bank.lending.chit.common;

/**
 * A business-rule failure with a stable machine-readable code. Callers switch on {@code code}, not on the message.
 * It carries no HTTP status on purpose: the calculation and rules code has no Spring dependency. The REST layer maps
 * a code to a status in one place ({@code config/ErrorStatus}).
 */
public class ChitException extends RuntimeException {

    private final String code;

    public ChitException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ChitException notFound(String what, String ref) {
        return new ChitException("NOT_FOUND", what + " not found: " + ref);
    }

    public static ChitException badRequest(String code, String message) {
        return new ChitException(code, message);
    }

    public static ChitException forbidden(String code, String message) {
        return new ChitException(code, message);
    }

    public static ChitException conflict(String code, String message) {
        return new ChitException(code, message);
    }
}
