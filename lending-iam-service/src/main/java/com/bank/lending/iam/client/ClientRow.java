package com.bank.lending.iam.client;

import java.time.Instant;
import java.util.List;

public record ClientRow(String clientId, String name, String kind, String secretHash, String previousSecretHash,
                        Instant previousValidUntil, List<String> grantTypes, List<String> redirectUris,
                        List<String> postLogoutUris, List<String> scopes, int accessTtlSeconds, int refreshTtlSeconds,
                        String status, Instant tokensValidAfter, Instant createdAt, String createdBy) {

    public boolean isPublic() { return "PUBLIC".equals(kind); }
    public boolean active() { return "ACTIVE".equals(status); }
}
