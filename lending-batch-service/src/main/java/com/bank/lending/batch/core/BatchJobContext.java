package com.bank.lending.batch.core;

import java.time.LocalDate;

/**
 * Per-step-execution access to the current run's businessDate and job_params_json, without
 * threading them through every reader/processor/writer method signature. Populated by
 * {@code BatchJobRegistry} from the Spring Batch {@code StepExecution}'s JobParameters /
 * ExecutionContext at the start of each step (step-scoped beans see it via
 * {@code @Value("#{stepExecutionContext['businessDate']}")}-style late binding in practice;
 * this class exists as the one place that convention is documented and, where a bean isn't
 * step-scoped, resolvable programmatically).
 */
public final class BatchJobContext {

    private static final ThreadLocal<LocalDate> BUSINESS_DATE = new ThreadLocal<>();
    private static final ThreadLocal<String> JOB_PARAMS_JSON = new ThreadLocal<>();

    private BatchJobContext() {
    }

    public static void set(LocalDate businessDate, String jobParamsJson) {
        BUSINESS_DATE.set(businessDate);
        JOB_PARAMS_JSON.set(jobParamsJson);
    }

    public static LocalDate businessDate() {
        LocalDate date = BUSINESS_DATE.get();
        return date != null ? date : LocalDate.now();
    }

    public static String jobParamsJson() {
        return JOB_PARAMS_JSON.get();
    }

    public static void clear() {
        BUSINESS_DATE.remove();
        JOB_PARAMS_JSON.remove();
    }
}
