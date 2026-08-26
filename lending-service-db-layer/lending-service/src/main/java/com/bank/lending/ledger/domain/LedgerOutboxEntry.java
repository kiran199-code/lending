package com.bank.lending.ledger.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Outbox pattern: written in the same DB transaction as the business change
 * (e.g. disbursement, EMI posting). A separate poller pushes PENDING rows to
 * the core banking ledger and marks them POSTED/FAILED, so ledger downtime
 * doesn't block loan servicing but postings still eventually reconcile.
 */
@Entity
@Table(name = "ledger_outbox")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerOutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_id")
    private Long outboxId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private EventType eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "posting_status", nullable = false, length = 15)
    private PostingStatus postingStatus;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "last_attempted_at")
    private OffsetDateTime lastAttemptedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum EventType { DISBURSEMENT, EMI_POSTING, FORECLOSURE, CHARGE_REVERSAL }

    public enum PostingStatus { PENDING, POSTED, FAILED }
}
