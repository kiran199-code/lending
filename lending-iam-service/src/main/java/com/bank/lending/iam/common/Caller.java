package com.bank.lending.iam.common;

import java.time.Instant;
import java.util.Set;

/**
 * The authenticated caller of an API call, as read from its access token.
 * @param name      username (people) or client id (service accounts)
 * @param kind      STAFF, CUSTOMER or SERVICE
 * @param mfaAt     the instant a second factor was last verified, or null
 * @param issuedAt  when the token was issued
 */
public record Caller(String name, String kind, Set<String> permissions, Instant mfaAt, Instant issuedAt) {
    public boolean isService() { return "SERVICE".equals(kind); }
    public boolean isStaff() { return "STAFF".equals(kind); }
    public String actorType() { return isService() ? "CLIENT" : "USER"; }
    public boolean has(String permission) { return permissions.contains(permission); }
}
