package com.bank.lending.demand.domain;

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

/** One day's penal interest on one demand: the unpaid principal and interest it was charged on, the rate and the amount. */
@Entity
@Table(name = "penal_interest")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PenalInterest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "penal_id")
    private Long penalId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "demand_id", nullable = false)
    private Long demandId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "base_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal baseAmount;

    /** Annual rate in % that applied on that day (the product's rate then, kept so later rate changes do not rewrite history). */
    @Column(name = "annual_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal annualRate;

    @Column(name = "amount", nullable = false, precision = 15, scale = 4)
    private BigDecimal amount;

    @Builder.Default
    @Column(name = "suspended", nullable = false)
    private Boolean suspended = Boolean.FALSE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
