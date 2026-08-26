package com.bank.lending.ledger.dto;

import com.bank.lending.ledger.domain.LedgerOutboxEntry;

import java.time.OffsetDateTime;

public record LedgerOutboxEntryResponse(
        Long outboxId,
        Long accountId,
        LedgerOutboxEntry.EventType eventType,
        String payload,
        LedgerOutboxEntry.PostingStatus postingStatus,
        Integer retryCount,
        OffsetDateTime lastAttemptedAt,
        OffsetDateTime createdAt
) {
    public static LedgerOutboxEntryResponse from(LedgerOutboxEntry e) {
        return new LedgerOutboxEntryResponse(
                e.getOutboxId(), e.getAccountId(), e.getEventType(), e.getPayload(),
                e.getPostingStatus(), e.getRetryCount(), e.getLastAttemptedAt(), e.getCreatedAt()
        );
    }
}
