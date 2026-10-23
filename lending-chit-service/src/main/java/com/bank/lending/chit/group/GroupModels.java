package com.bank.lending.chit.group;

import com.bank.lending.chit.domain.Frequency;
import com.bank.lending.chit.domain.GroupStatus;
import com.bank.lending.chit.domain.rules.ChitRules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database rows of the group tables, as the services use them. */
public final class GroupModels {
    private GroupModels() {
    }

    public record GroupRow(long id, String groupCode, String name, String foremanRef, GroupStatus status,
                           BigDecimal chitValue, int ticketCount, int cycleCount, BigDecimal subscription,
                           Frequency frequency, LocalDate startDate, int currentRulesVersion, OffsetDateTime activatedAt,
                           String activatedBy, OffsetDateTime cancelledAt, String cancelledBy, String cancelReason,
                           long version, OffsetDateTime createdAt, String createdBy) {
    }

    public record RulesVersionRow(long groupId, int versionNo, ChitRules rules, boolean frozen, OffsetDateTime frozenAt,
                                  OffsetDateTime createdAt, String createdBy) {
    }

    public record CycleRow(long id, int cycleNo, String status, LocalDate dueDate, LocalDate auctionDate) {
    }
}
