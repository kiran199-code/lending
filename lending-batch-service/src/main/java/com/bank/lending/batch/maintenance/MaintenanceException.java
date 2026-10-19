package com.bank.lending.batch.maintenance;

import org.springframework.http.HttpStatus;

/** A refused maintenance request: carries the HTTP status and a short machine-readable code. */
public class MaintenanceException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public MaintenanceException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
