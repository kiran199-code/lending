package com.bank.lending.iam.common;

/** Counters (sign-ins, approvals, denials). The Spring configuration binds this to Micrometer. */
public interface MetricsSink {
    void count(String name, String tag, String value);

    MetricsSink NOOP = (name, tag, value) -> { };
}
