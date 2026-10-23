package com.bank.lending.chit.group;

import com.bank.lending.chit.calendar.ScheduledCycle;
import com.bank.lending.chit.common.RuleViolation;
import com.bank.lending.chit.domain.Frequency;
import com.bank.lending.chit.domain.GroupStatus;
import com.bank.lending.chit.domain.rules.ChitRules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class GroupDtos {
    private GroupDtos() {
    }

    /** {@code groupCode}: 3 to 30 characters, capital letters, digits, '-' and '_'. Rules may leave optional fields out. */
    public record CreateGroupRequest(String groupCode, String name, String foremanRef, ChitRules rules) {
    }

    public record GroupView(String groupCode, String name, String foremanRef, GroupStatus status, BigDecimal chitValue,
                            int ticketCount, int cycleCount, BigDecimal subscription, Frequency frequency, LocalDate startDate,
                            int rulesVersion, int ticketsEnrolled, int seatsLeft, OffsetDateTime activatedAt, String activatedBy,
                            OffsetDateTime cancelledAt, String cancelledBy, String cancelReason, long version,
                            OffsetDateTime createdAt, String createdBy, Boolean replayed) {
        /** The same group, flagged as the answer to a repeated request (same idempotency key and body). */
        public GroupView asReplay() {
            return new GroupView(groupCode, name, foremanRef, status, chitValue, ticketCount, cycleCount, subscription, frequency,
                    startDate, rulesVersion, ticketsEnrolled, seatsLeft, activatedAt, activatedBy, cancelledAt, cancelledBy,
                    cancelReason, version, createdAt, createdBy, Boolean.TRUE);
        }
    }

    public record GroupPage(List<GroupView> items, long total, int limit, int offset) {
    }

    public record RulesView(int versionNo, boolean frozen, OffsetDateTime createdAt, String createdBy, ChitRules rules) {
    }

    public record RulesVersionSummary(int versionNo, boolean frozen, OffsetDateTime frozenAt, OffsetDateTime createdAt, String createdBy) {
    }

    public record PreviewRequest(ChitRules rules) {
    }

    /** {@code valid} is true when the rules reconcile; {@code schedule} is filled when they do and the calendar could be built. */
    public record PreviewResult(boolean valid, List<RuleViolation> violations, ChitRules effectiveRules,
                                List<ScheduledCycle> schedule, String scheduleError) {
    }

    public record CycleView(int cycleNo, String status, LocalDate dueDate, LocalDate auctionDate) {
    }
}
