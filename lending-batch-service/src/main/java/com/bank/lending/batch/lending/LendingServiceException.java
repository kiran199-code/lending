package com.bank.lending.batch.lending;

/**
 * A call to lending-service failed (non-2xx after retries, or the service was unreachable).
 *
 * <p>Extends {@link IllegalStateException} on purpose: that is the exception type the seeded
 * {@code batch_job_config.skippable_exceptions} lists, so a per-account failure is skipped and
 * dead-lettered (visible in the admin portal's job errors) instead of aborting the whole run. If
 * lending-service is down every account fails and the job fails once the skip limit is reached.
 */
public class LendingServiceException extends IllegalStateException {

    private final int httpStatus;

    public LendingServiceException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /** HTTP status of the failed response, or 0 when no response was received (I/O error, timeout). */
    public int httpStatus() {
        return httpStatus;
    }
}
