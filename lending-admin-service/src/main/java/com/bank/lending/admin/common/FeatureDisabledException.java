package com.bank.lending.admin.common;

/** The feature needs configuration this deployment does not have (maps to 503). */
public class FeatureDisabledException extends RuntimeException {
    public FeatureDisabledException(String message) {
        super(message);
    }
}
