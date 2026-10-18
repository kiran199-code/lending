package com.bank.lending.iam.access;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Row types of the role-based access tables. */
public final class RbacModels {
    private RbacModels() {}

    public record PermissionRow(String code, String module, String description, String risk, boolean requiresMfa, String appliesTo) {}

    public record RoleRow(String code, String name, String kind, String description, boolean mfaRequired, boolean systemRole, String status) {}

    public record LimitRow(String roleCode, String permissionCode, String currency, BigDecimal maxAmount) {}

    public record Assignment(long assignmentId, long userId, String roleCode, LocalDate validFrom, LocalDate validTo, String grantedBy, Instant grantedAt) {}

    public record ScopeRow(String type, String value) {}

    public record RoleGrant(String roleCode, LocalDate validFrom, LocalDate validTo) {}
}
