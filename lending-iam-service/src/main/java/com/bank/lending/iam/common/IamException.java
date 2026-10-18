package com.bank.lending.iam.common;

import org.springframework.http.HttpStatus;

/** Business-rule failure with a stable machine-readable code (callers switch on {@code code}, not the message). */
public class IamException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final java.util.List<String> details;

    public IamException(HttpStatus status, String code, String message) {
        this(status, code, message, java.util.List.of());
    }

    public IamException(HttpStatus status, String code, String message, java.util.List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    /** Extra lines for the caller, for example each password rule that was broken. */
    public java.util.List<String> details() { return details; }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static IamException notFound(String what, String ref) {
        return new IamException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found: " + ref);
    }
    public static IamException badRequest(String code, String message) {
        return new IamException(HttpStatus.BAD_REQUEST, code, message);
    }
    public static IamException unauthorized(String code, String message) {
        return new IamException(HttpStatus.UNAUTHORIZED, code, message);
    }
    public static IamException forbidden(String code, String message) {
        return new IamException(HttpStatus.FORBIDDEN, code, message);
    }
    public static IamException conflict(String code, String message) {
        return new IamException(HttpStatus.CONFLICT, code, message);
    }
    public static IamException conflict(String code, String message, java.util.List<String> details) {
        return new IamException(HttpStatus.CONFLICT, code, message, details);
    }
    public static IamException unprocessable(String code, String message) {
        return new IamException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
    public static IamException unprocessable(String code, String message, java.util.List<String> details) {
        return new IamException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
    }
}
