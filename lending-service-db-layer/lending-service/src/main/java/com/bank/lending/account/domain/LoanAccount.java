package com.bank.lending.account.domain;

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
@Table(name = "loan_account")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoanAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "account_number", nullable = false, unique = true, length = 20)
    private String accountNumber;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "sanction_ref_id", nullable = false, unique = true)
    private Long sanctionRefId;

    @Column(name = "product_code", nullable = false, length = 10)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 15)
    private AccountType accountType;

    @Column(name = "sanctioned_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal sanctionedAmount;

    @Column(name = "disbursed_amount", precision = 15, scale = 2)
    private BigDecimal disbursedAmount;

    @Column(name = "tenure_months", nullable = false)
    private Integer tenureMonths;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_type", nullable = false, length = 10)
    private RateType rateType;

    @Column(name = "interest_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "benchmark_rate_ref", length = 20)
    private String benchmarkRateRef;

    @Column(name = "spread", precision = 6, scale = 4)
    private BigDecimal spread;

    @Convert(converter = DayCountConventionConverter.class)
    @Column(name = "day_count_convention", nullable = false, length = 10)
    private DayCountConvention dayCountConvention;

    @Column(name = "emi_amount", precision = 15, scale = 2)
    private BigDecimal emiAmount;

    @Column(name = "emi_start_date")
    private LocalDate emiStartDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 30)
    private AccountStatus accountStatus;

    @Column(name = "mortgage_id")
    private Long mortgageId;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public enum AccountType { UNSECURED, MORTGAGE }

    public enum RateType { FIXED, FLOATING }

    /**
     * DB stores these as 'ACT/365', 'ACT/ACT', '30/360' (see V1 migration CHECK constraint).
     * Java enum names can't contain '/', so mapping is handled by DayCountConventionConverter.
     */
    public enum DayCountConvention {
        ACT_365,
        ACT_ACT,
        THIRTY_360
    }

    public enum AccountStatus {
        PENDING_DISBURSEMENT, ACTIVE,
        NPA_SMA0, NPA_SMA1, NPA_SMA2,
        NPA_SUBSTANDARD, NPA_DOUBTFUL, NPA_LOSS,
        CLOSED, FORECLOSED, WRITTEN_OFF
    }
}
