package com.bank.lending.common.exception;

public class InvalidAccountStateException extends RuntimeException {
    public InvalidAccountStateException(String message) {
        super(message);
    }
}
