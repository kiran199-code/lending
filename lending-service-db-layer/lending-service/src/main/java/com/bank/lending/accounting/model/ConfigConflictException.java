package com.bank.lending.accounting.model;

/** A configuration change that is not allowed in the object's current state (wrong status, maker equals checker). */
public class ConfigConflictException extends RuntimeException {
    public ConfigConflictException(String message) {
        super(message);
    }
}
