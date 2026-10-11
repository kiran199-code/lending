package com.bank.lending.product.domain;

import com.bank.lending.account.domain.DayCountConventionConverter;
import com.bank.lending.account.domain.LoanAccount;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Product master. The terms here are copied onto a new loan account when the product is selected;
 * the min/max columns are the bands the account's own values must stay within (null = no limit).
 */
@Entity
@Table(name = "loan_product")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoanProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_code", nullable = false, unique = true, length = 10)
    private String productCode;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 15)
    private LoanAccount.AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_type", nullable = false, length = 10)
    private LoanAccount.RateType rateType;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 20)
    private LoanAccount.ScheduleType scheduleType = LoanAccount.ScheduleType.EMI;

    @Builder.Default
    @Convert(converter = DayCountConventionConverter.class)
    @Column(name = "day_count_convention", nullable = false, length = 10)
    private LoanAccount.DayCountConvention dayCountConvention = LoanAccount.DayCountConvention.ACT_365;

    @Column(name = "benchmark_rate_ref", length = 20)
    private String benchmarkRateRef;

    @Column(name = "default_interest_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal defaultInterestRate;

    @Column(name = "default_spread", precision = 6, scale = 4)
    private BigDecimal defaultSpread;

    @Column(name = "min_interest_rate", precision = 6, scale = 4)
    private BigDecimal minInterestRate;

    @Column(name = "max_interest_rate", precision = 6, scale = 4)
    private BigDecimal maxInterestRate;

    @Column(name = "min_spread", precision = 6, scale = 4)
    private BigDecimal minSpread;

    @Column(name = "max_spread", precision = 6, scale = 4)
    private BigDecimal maxSpread;

    @Column(name = "min_sanctioned_amount", precision = 15, scale = 2)
    private BigDecimal minSanctionedAmount;

    @Column(name = "max_sanctioned_amount", precision = 15, scale = 2)
    private BigDecimal maxSanctionedAmount;

    @Column(name = "min_tenure_months")
    private Integer minTenureMonths;

    @Column(name = "max_tenure_months")
    private Integer maxTenureMonths;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public enum Status { ACTIVE, INACTIVE }
}
