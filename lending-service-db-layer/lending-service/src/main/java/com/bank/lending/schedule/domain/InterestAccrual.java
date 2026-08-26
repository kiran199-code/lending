package com.bank.lending.schedule.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Daily interest accrual. UNIQUE(account_id, business_date) at the DB level
 * (see V5 migration) is what makes the daily batch job idempotent — combine
 * with an ON CONFLICT (account_id, business_date) DO NOTHING in the batch
 * writer rather than relying on this entity's PK alone, since the PK here
 * is a synthetic (accrual_id, business_date) pair required by partitioning,
 * not the natural business key.
 */
@Entity
@Table(name = "interest_accrual")
@IdClass(InterestAccrualId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterestAccrual {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "accrual_id")
    private Long accrualId;

    @Id
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "accrued_amount", nullable = false, precision = 15, scale = 4)
    private BigDecimal accruedAmount;

    @Column(name = "outstanding_principal", nullable = false, precision = 15, scale = 2)
    private BigDecimal outstandingPrincipal;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
