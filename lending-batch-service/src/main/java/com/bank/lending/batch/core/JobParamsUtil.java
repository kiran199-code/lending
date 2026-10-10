package com.bank.lending.batch.core;

import org.springframework.batch.core.JobParameters;

import java.time.LocalDate;

/**
 * Reads the {@code businessDate} job parameter. It is stored as an ISO-8601 string (identifying
 * parameter) so the code depends only on the long-stable String accessors of
 * {@link JobParameters}, not on LocalDate-specific accessors that differ between Spring Batch
 * releases.
 */
public final class JobParamsUtil {

    public static final String BUSINESS_DATE = "businessDate";

    private JobParamsUtil() {
    }

    /** @return the run's business date, or null if the parameter is absent. */
    public static LocalDate businessDate(JobParameters parameters) {
        if (parameters == null) {
            return null;
        }
        String value = parameters.getString(BUSINESS_DATE);
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }
}
