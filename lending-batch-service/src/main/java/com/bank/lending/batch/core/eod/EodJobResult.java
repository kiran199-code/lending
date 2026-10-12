package com.bank.lending.batch.core.eod;

/** Result of one job within an end-of-day run. status is a Spring Batch status, or SKIPPED when it had already completed. */
public record EodJobResult(String jobName, String status, String message) {

    public boolean ok() {
        return "COMPLETED".equals(status) || "SKIPPED".equals(status);
    }
}
