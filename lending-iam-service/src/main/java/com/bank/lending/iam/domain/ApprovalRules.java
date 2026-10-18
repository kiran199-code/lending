package com.bank.lending.iam.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** The rules of maker-checker (section 6) that do not need the database. */
public final class ApprovalRules {
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";
    public static final String CONSUMED = "CONSUMED";

    private ApprovalRules() {}

    public record ActionView(String status, String maker, Instant expiresAt, Instant consumeDeadline, int approvalsNeeded) {}

    /**
     * Preconditions for a decision by {@code approver}. Returns null when the approver may go on to the
     * permission, scope, ceiling and step-up checks, or the error code.
     */
    public static String blockDecision(ActionView a, String approver, Set<String> earlierApprovers, Instant now) {
        if (!PENDING.equals(a.status())) return "ACTION_NOT_PENDING";
        if (!now.isBefore(a.expiresAt())) return "ACTION_EXPIRED";
        if (a.maker().equalsIgnoreCase(approver)) return "MAKER_CANNOT_APPROVE";
        for (String e : earlierApprovers) if (e.equalsIgnoreCase(approver)) return "ALREADY_DECIDED";
        return null;
    }

    /** After an approval: how many approvals the action then has and whether it is now approved. */
    public static boolean isApproved(int approvalsSoFar, int approvalsNeeded) {
        return approvalsSoFar >= approvalsNeeded;
    }

    public static Instant consumeDeadline(Instant approvedAt, int consumeHours) {
        return approvedAt.plus(Duration.ofHours(consumeHours));
    }

    public static boolean canCancel(ActionView a, String caller) {
        return PENDING.equals(a.status()) && a.maker().equalsIgnoreCase(caller);
    }
}
