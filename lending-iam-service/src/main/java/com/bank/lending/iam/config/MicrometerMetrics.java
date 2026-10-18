package com.bank.lending.iam.config;

import com.bank.lending.iam.common.MetricsSink;
import io.micrometer.core.instrument.MeterRegistry;

public class MicrometerMetrics implements MetricsSink {
    private final MeterRegistry registry;

    public MicrometerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void count(String name, String tag, String value) {
        registry.counter(name, tag, value).increment();
    }
}
