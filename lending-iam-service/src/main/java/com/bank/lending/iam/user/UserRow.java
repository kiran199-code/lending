package com.bank.lending.iam.user;

import java.time.Instant;

/** One row of app_user. Secrets stay in their encrypted or hashed form. */
public record UserRow(long id, String username, String kind, String displayName, String email, String mobile,
                      String customerId, String status, String passwordHash, Instant passwordChangedAt,
                      boolean mustChangePassword, int failedAttempts, Instant lockedUntil, boolean mfaEnabled,
                      String mfaSecretEnc, String mfaPendingEnc, Long mfaLastStep, Instant tokensValidAfter,
                      Instant lastLoginAt, boolean bootstrap, Instant createdAt, String createdBy, long version) {

    public boolean customer() { return "CUSTOMER".equals(kind); }
}
