package com.bank.lending.iam.approval;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ApprovalDtos {
    private ApprovalDtos() {}

    public record TypeView(String code, String name, String makerPermission, String checkerPermission, int approvalsRequired, int pendingHours,
                           int consumeHours, String callbackUrl, boolean active) {}

    /** Returned when a type is saved: the callback secret appears only when the callback subscription was just created. */
    public record TypeSaved(TypeView type, String callbackSecret) {}

    public record CreateTypeRequest(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 120) String name, @NotBlank String makerPermission,
                                    @NotBlank String checkerPermission, Integer approvalsRequired, Integer pendingHours, Integer consumeHours,
                                    @Size(max = 300) String callbackUrl) {}

    public record UpdateTypeRequest(@NotBlank @Size(max = 120) String name, @NotNull Integer approvalsRequired, @NotNull Integer pendingHours,
                                    @NotNull Integer consumeHours, @Size(max = 300) String callbackUrl, @NotNull Boolean active) {}

    public record SubmitRequest(@NotBlank String type, @NotBlank @Size(max = 120) String targetRef, @NotBlank @Size(max = 300) String summary,
                                @NotBlank String payload, @PositiveOrZero BigDecimal amount, @Size(max = 3) String currency,
                                @Size(max = 20) String branch, @Size(max = 40) String product, @Size(max = 60) String onBehalfOf) {}

    public record DecideRequest(@Size(max = 500) String comment) {}

    public record ConsumeRequest(@NotBlank @Size(max = 120) String reference) {}

    public record DecisionView(String approver, String decision, String comment, Instant decidedAt) {}

    public record ActionView(String id, String type, String targetRef, String summary, String payload, String payloadHash, BigDecimal amount, String currency,
                             String branch, String product, String status, String maker, String submittedBy, int approvalsNeeded, int approvalsGiven,
                             Instant createdAt, Instant expiresAt, Instant decidedAt, Instant consumeDeadline, Instant consumedAt, String consumedBy,
                             String consumerRef, String comment, List<DecisionView> decisions) {}

    /** The result of a decision. {@code error} is set when the change must be reported as a failure after it was recorded (late decision). */
    public record DecisionOutcome(ActionView action, String error) {}

    public record PageView(List<ActionView> items, long total, int page, int size) {}
}
