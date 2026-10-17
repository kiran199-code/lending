package com.bank.lending.accounting.service;

/** Outcome of posting one accounting event. */
public record PostResult(Status status, Long journalId, String journalNo, String note) {

    public enum Status {
        /** A journal was written. */
        POSTED,
        /** The idempotency key was already used; the earlier journal (if any) stands. */
        DUPLICATE,
        /** Nothing to post: accounting or the event is switched off. */
        SKIPPED,
        /** Every amount was zero, so no journal was written. */
        ZERO,
        /** A non-blocking event failed; the failure is in the exception queue and the business change went ahead. */
        FAILED_RECORDED
    }

    public static PostResult posted(long id, String no) {
        return new PostResult(Status.POSTED, id, no, null);
    }

    public static PostResult duplicate(Long id, String no) {
        return new PostResult(Status.DUPLICATE, id, no, null);
    }

    public static PostResult skipped(String why) {
        return new PostResult(Status.SKIPPED, null, null, why);
    }

    public static PostResult zero() {
        return new PostResult(Status.ZERO, null, null, "all amounts are zero");
    }

    public static PostResult failedRecorded(String message) {
        return new PostResult(Status.FAILED_RECORDED, null, null, message);
    }

    public boolean journalled() {
        return status == Status.POSTED;
    }
}
