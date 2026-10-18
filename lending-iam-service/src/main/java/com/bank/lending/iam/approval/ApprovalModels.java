package com.bank.lending.iam.approval;

import java.math.BigDecimal;
import java.time.Instant;

public final class ApprovalModels {
    private ApprovalModels() {}

    public record TypeRow(String code, String name, String makerPermission, String checkerPermission, int approvalsRequired,
                          int pendingHours, int consumeHours, String callbackUrl, boolean active) {}

    public record ActionRow(String id, String typeCode, String targetRef, String summary, String payload, String payloadHash,
                            BigDecimal amount, String currency, String branch, String product, String status, String maker,
                            String submittedBy, String idempotencyKey, int approvalsNeeded, Instant createdAt, Instant expiresAt,
                            Instant decidedAt, Instant consumeDeadline, Instant consumedAt, String consumedBy, String consumerRef,
                            String decisionComment, long version) {}

    public record DecisionRow(long id, String actionId, String approver, String decision, String comment, Instant decidedAt) {}
}
