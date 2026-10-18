package com.bank.lending.iam.access;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public final class RoleDtos {
    private RoleDtos() {}

    public record PermissionView(String code, String module, String description, String risk, boolean requiresMfa, String appliesTo) {}

    public record LimitView(String permission, String currency, BigDecimal maxAmount) {}

    public record RoleView(String code, String name, String kind, String description, boolean mfaRequired, boolean systemRole, String status,
                           List<String> permissions, List<LimitView> limits) {}

    public record CreateRoleRequest(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 80) String name, @NotBlank String kind,
                                    @Size(max = 300) String description, Boolean mfaRequired, List<String> permissions) {}

    public record UpdateRoleRequest(@NotBlank @Size(max = 80) String name, @Size(max = 300) String description, @NotNull Boolean mfaRequired, @NotBlank String status) {}

    public record ReplacePermissionsRequest(@NotNull List<String> permissions) {}

    public record LimitRequest(@NotBlank String permission, @NotBlank @Size(max = 3) String currency, @NotNull @PositiveOrZero BigDecimal maxAmount) {}

    public record ReplaceLimitsRequest(@NotNull @Valid List<LimitRequest> limits) {}

    public record SodRuleView(long id, String permissionA, String permissionB, String description) {}

    public record CreateSodRuleRequest(@NotBlank String permissionA, @NotBlank String permissionB, @NotBlank @Size(max = 200) String description) {}
}
