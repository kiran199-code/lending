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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
    @Column(name = "repayment_frequency", nullable = false, length = 15)
    private LoanAccount.RepaymentFrequency repaymentFrequency = LoanAccount.RepaymentFrequency.MONTHLY;

    /** Comma-separated {@link LoanAccount.RepaymentFrequency} names an account may choose from. */
    @Builder.Default
    @Column(name = "allowed_repayment_frequencies", nullable = false, length = 60)
    private String allowedRepaymentFrequencies = "MONTHLY";

    @Builder.Default
    @Column(name = "moratorium_months", nullable = false)
    private Integer moratoriumMonths = 0;

    @Column(name = "max_moratorium_months")
    private Integer maxMoratoriumMonths;

    @Builder.Default
    @Column(name = "balloon_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal balloonPercent = BigDecimal.ZERO;

    @Column(name = "max_balloon_percent", precision = 5, scale = 2)
    private BigDecimal maxBalloonPercent;

    @Builder.Default
    @Column(name = "step_up_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal stepUpPercent = BigDecimal.ZERO;

    @Column(name = "max_step_up_percent", precision = 5, scale = 2)
    private BigDecimal maxStepUpPercent;

    /** Switch for penal interest on overdue instalments of accounts of this product. */
    @Builder.Default
    @Column(name = "penal_interest_enabled", nullable = false)
    private Boolean penalInterestEnabled = Boolean.TRUE;

    /** Penal interest rate, % a year, charged on the unpaid principal and interest of an overdue demand. */
    @Builder.Default
    @Column(name = "penal_interest_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal penalInterestRate = new BigDecimal("24.0000");

    /** Days after the due date before penal interest starts (0 = the day after the due date). */
    @Builder.Default
    @Column(name = "penal_grace_days", nullable = false)
    private Integer penalGraceDays = 0;

    /** Most penal interest one demand may collect, as a % of that demand's principal + interest. Null = no cap. */
    @Column(name = "penal_cap_percent", precision = 6, scale = 2)
    private BigDecimal penalCapPercent;

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

    /** The allowed repayment frequencies as a list (never empty: falls back to the default frequency). */
    public List<LoanAccount.RepaymentFrequency> allowedFrequencies() {
        List<LoanAccount.RepaymentFrequency> list = new ArrayList<>();
        if (allowedRepaymentFrequencies != null) {
            for (String name : allowedRepaymentFrequencies.split(",")) {
                if (!name.isBlank()) {
                    list.add(LoanAccount.RepaymentFrequency.valueOf(name.trim()));
                }
            }
        }
        if (list.isEmpty()) {
            list.add(repaymentFrequency);
        }
        return list;
    }

    public static String joinFrequencies(List<LoanAccount.RepaymentFrequency> frequencies) {
        return frequencies.stream().map(Enum::name).collect(Collectors.joining(","));
    }
}
