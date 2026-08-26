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
 * One row per installment. schedule_version increments on regeneration
 * (rate reset / part-payment). Old versions are retained for audit —
 * the "current" schedule for an account is the max(schedule_version).
 */
@Entity
@Table(name = "amortization_schedule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AmortizationScheduleEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "installment_number", nullable = false)
    private Integer installmentNumber;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "principal_component", nullable = false, precision = 15, scale = 2)
    private BigDecimal principalComponent;

    @Column(name = "interest_component", nullable = false, precision = 15, scale = 2)
    private BigDecimal interestComponent;

    @Column(name = "outstanding_balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal outstandingBalance;

    @Enumerated(EnumType.STRING)
    @Column(name = "installment_status", nullable = false, length = 15)
    private InstallmentStatus installmentStatus;

    @Column(name = "schedule_version", nullable = false)
    private Integer scheduleVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum InstallmentStatus { PENDING, PAID, OVERDUE }
}
