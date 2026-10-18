package com.bank.lending.iam.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response bodies that belong to the web layer only. */
public final class ApiDtos {
    private ApiDtos() {}

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(max = 200) String newPassword) {}

    public record CodeRequest(@NotBlank @Size(max = 20) String code) {}

    public record EnrolmentView(String secret, String otpauthUri) {}

    public record RecoveryCodesView(List<String> recoveryCodes) {}

    public record ConsentChangeRequest(@NotNull Boolean granted) {}

    public record ActivateRequest(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 200) String newPassword, List<String> acceptedPurposes) {}

    public record CheckBody(@NotBlank String permission, String subject, String branch, String product, String currency, BigDecimal amount,
                            String accountRef, String accountCustomerId, Instant mfaAt) {}

    public record AuditRowView(long id, Instant occurredAt, String actorType, String actor, String action, String targetType, String targetId,
                               String outcome, String ip, String correlationId, Map<String, Object> detail, String rowHash) {}

    public record AuditPageView(List<AuditRowView> items, long total, int page, int size) {}

    public record Message(String message) {}
}
