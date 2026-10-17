package com.bank.lending.demand.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * What fell due on one instalment: a principal part and an interest part, with the amount paid so far on each.
 * At most one per instalment of an account (unique on account_id and installment_number).
 */
@Entity
@Table(name = "loan_demand")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoanDemand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "demand_id")
    private Long demandId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** The schedule version the instalment belonged to when the demand was raised. */
    @Column(name = "schedule_version", nullable = false)
    private Integer scheduleVersion;

    @Column(name = "installment_number", nullable = false)
    private Integer installmentNumber;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "principal_due", nullable = false, precision = 15, scale = 2)
    private BigDecimal principalDue;

    @Column(name = "interest_due", nullable = false, precision = 15, scale = 2)
    private BigDecimal interestDue;

    @Builder.Default
    @Column(name = "principal_paid", nullable = false, precision = 15, scale = 2)
    private BigDecimal principalPaid = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "interest_paid", nullable = false, precision = 15, scale = 2)
    private BigDecimal interestPaid = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private DemandStatus status;

    @Column(name = "cleared_date")
    private LocalDate clearedDate;

    /** The business date the demand was created on; later than the due date when a run was missed. */
    @Column(name = "raised_on", nullable = false)
    private LocalDate raisedOn;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public BigDecimal principalOutstanding() {
        return principalDue.subtract(principalPaid);
    }

    public BigDecimal interestOutstanding() {
        return interestDue.subtract(interestPaid);
    }

    public BigDecimal outstanding() {
        return principalOutstanding().add(interestOutstanding());
    }

    public enum DemandStatus { OPEN, PART_PAID, CLEARED }
}
