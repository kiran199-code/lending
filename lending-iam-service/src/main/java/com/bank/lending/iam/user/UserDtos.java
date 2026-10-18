package com.bank.lending.iam.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class UserDtos {
    private UserDtos() {}

    public record CreateUserRequest(@NotBlank @Size(max = 60) String username, @NotBlank String kind, @NotBlank @Size(max = 120) String displayName,
                                    @Size(max = 160) String email, @Size(max = 30) String mobile, @Size(max = 40) String customerId) {}

    public record UpdateUserRequest(@NotBlank @Size(max = 120) String displayName, @Size(max = 160) String email, @Size(max = 30) String mobile) {}

    public record RoleGrantRequest(@NotBlank String roleCode, LocalDate validFrom, LocalDate validTo) {}

    public record AssignRolesRequest(@NotNull @Valid List<RoleGrantRequest> roles) {}

    public record ScopeRequest(@NotBlank String type, @NotBlank String value) {}

    public record AssignScopesRequest(@NotNull @Valid List<ScopeRequest> scopes) {}

    public record RoleAssignmentView(String roleCode, LocalDate validFrom, LocalDate validTo, String grantedBy, Instant grantedAt) {}

    public record UserView(long id, String username, String kind, String displayName, String email, String mobile, String customerId, String status,
                           boolean mfaEnabled, boolean mustChangePassword, Instant passwordChangedAt, Instant lastLoginAt, Instant lockedUntil,
                           boolean bootstrap, Instant createdAt, String createdBy, List<RoleAssignmentView> roles, Map<String, List<String>> scopes) {}

    /** Returned once at creation or reset: the secret is not recoverable afterwards. */
    public record CreatedUser(UserView user, String secretKind, String secret, Instant secretExpiresAt) {}

    public record SecretView(String secretKind, String secret, Instant secretExpiresAt) {}

    public record AccessView(String username, String kind, String status, List<String> roles, List<String> permissions,
                             Map<String, List<Map<String, String>>> ceilings, Map<String, List<String>> scopes) {}

    public record PageView<T>(List<T> items, long total, int page, int size) {}
}
