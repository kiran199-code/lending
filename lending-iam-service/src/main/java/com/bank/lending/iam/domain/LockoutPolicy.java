package com.bank.lending.iam.domain;

import java.time.Duration;
import java.time.Instant;

/** Consecutive-failure lockout: after {@code maxFailures} failures the user is locked for {@code lockFor}. */
public record LockoutPolicy(int maxFailures, Duration lockFor) {

    /** @param lockedUntil null when the failure did not lock the user */
    public record Outcome(int failedAttempts, Instant lockedUntil) {
        public boolean lockedNow() { return lockedUntil != null; }
    }

    public Outcome onFailure(int currentFailed, Instant now) {
        int n = currentFailed + 1;
        return n >= maxFailures ? new Outcome(n, now.plus(lockFor)) : new Outcome(n, null);
    }

    public boolean isLocked(Instant lockedUntil, Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }
}
