package com.bank.lending.common.exception;

/** The operation exists but has been switched off for this deployment (maps to HTTP 403). */
public class FeatureDisabledException extends RuntimeException {
    public FeatureDisabledException(String message) {
        super(message);
    }
}
