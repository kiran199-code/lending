package com.bank.lending.lcm.common;

import org.springframework.http.HttpStatus;

/** Business-rule failure with a stable machine-readable code (callers switch on {@code code}, not the message). */
public class LcmException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public LcmException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static LcmException notFound(String what, String ref) {
        return new LcmException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found: " + ref);
    }
    public static LcmException unprocessable(String code, String message) {
        return new LcmException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
    public static LcmException conflict(String code, String message) {
        return new LcmException(HttpStatus.CONFLICT, code, message);
    }
    public static LcmException badRequest(String code, String message) {
        return new LcmException(HttpStatus.BAD_REQUEST, code, message);
    }
    public static LcmException forbidden(String code, String message) {
        return new LcmException(HttpStatus.FORBIDDEN, code, message);
    }
}
