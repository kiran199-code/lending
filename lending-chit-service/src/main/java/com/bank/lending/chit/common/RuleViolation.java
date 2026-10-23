package com.bank.lending.chit.common;

/** One reason a configuration is rejected: a stable code, the field it is about, and a message for people. */
public record RuleViolation(String code, String field, String message) {
}
