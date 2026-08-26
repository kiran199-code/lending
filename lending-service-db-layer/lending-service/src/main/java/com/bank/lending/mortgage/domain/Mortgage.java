package com.bank.lending.mortgage.domain;

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

@Entity
@Table(name = "mortgage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Mortgage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mortgage_id")
    private Long mortgageId;

    @Column(name = "account_id", nullable = false, unique = true)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "property_type", nullable = false, length = 15)
    private PropertyType propertyType;

    @Column(name = "property_address", nullable = false, columnDefinition = "TEXT")
    private String propertyAddress;

    @Column(name = "valuation_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal valuationAmount;

    @Column(name = "valuation_date", nullable = false)
    private LocalDate valuationDate;

    @Column(name = "valuer_ref", length = 50)
    private String valuerRef;

    @Column(name = "ltv_ratio", nullable = false, precision = 5, scale = 2)
    private BigDecimal ltvRatio;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_status", nullable = false, length = 15)
    private ChargeStatus chargeStatus;

    @Column(name = "cersai_registration_ref", length = 50)
    private String cersaiRegistrationRef;

    @Column(name = "mortgage_deed_ref", length = 50)
    private String mortgageDeedRef;

    @Column(name = "insurance_policy_ref", length = 50)
    private String insurancePolicyRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "legal_verification_status", length = 15)
    private LegalVerificationStatus legalVerificationStatus;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public enum PropertyType { RESIDENTIAL, COMMERCIAL }

    public enum ChargeStatus { PENDING, REGISTERED, RELEASED }

    public enum LegalVerificationStatus { PENDING, VERIFIED, REJECTED }
}
