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

    /**
     * The business date of the run executing on this thread. There is deliberately no fallback to the
     * system clock: a job must run for the platform's business date (see BusinessDateProvider), and
     * silently using "today" would post to the wrong day.
     */
    public static LocalDate businessDate() {
        LocalDate date = BUSINESS_DATE.get();
        if (date == null) {
            throw new IllegalStateException("No business date in the job context — the run was not launched with a businessDate parameter");
        }
        return date;
    }

    public static String jobParamsJson() {
        return JOB_PARAMS_JSON.get();
    }

    /** The context of the current thread, for handing to another thread that runs part of the same step. */
    public record Snapshot(LocalDate businessDate, String jobParamsJson) {
    }

    public static Snapshot snapshot() {
        return new Snapshot(BUSINESS_DATE.get(), JOB_PARAMS_JSON.get());
    }

    public static void restore(Snapshot snapshot) {
        BUSINESS_DATE.set(snapshot.businessDate());
        JOB_PARAMS_JSON.set(snapshot.jobParamsJson());
    }

    public static void clear() {
        BUSINESS_DATE.remove();
        JOB_PARAMS_JSON.remove();
    }
}
