package com.bank.lending.mortgage.domain;

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

@Entity
@Table(name = "mortgage_valuation_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MortgageValuationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "valuation_id")
    private Long valuationId;

    @Column(name = "mortgage_id", nullable = false)
    private Long mortgageId;

    @Column(name = "valuation_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal valuationAmount;

    @Column(name = "valuation_date", nullable = false)
    private LocalDate valuationDate;

    @Column(name = "valuer_ref", length = 50)
    private String valuerRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "valuation_type", nullable = false, length = 20)
    private ValuationType valuationType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum ValuationType { INITIAL, PERIODIC_REVIEW, TRIGGER_BASED }
}
