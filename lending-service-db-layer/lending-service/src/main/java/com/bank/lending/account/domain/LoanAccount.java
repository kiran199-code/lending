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

    /** The loan_product this account was created from (its terms were defaulted from it). */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 20)
    private ScheduleType scheduleType = ScheduleType.EMI;

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

    /** How often instalments fall due. Tenure must be a whole number of these periods. */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "repayment_frequency", nullable = false, length = 15)
    private RepaymentFrequency repaymentFrequency = RepaymentFrequency.MONTHLY;

    /** Opening months in which only interest is paid (not used by INTEREST_ONLY, which is all interest). */
    @Builder.Default
    @Column(name = "moratorium_months", nullable = false)
    private Integer moratoriumMonths = 0;

    /** BALLOON schedules: share of the principal left to be paid with the last instalment. */
    @Builder.Default
    @Column(name = "balloon_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal balloonPercent = BigDecimal.ZERO;

    /** STEP_UP_EMI schedules: yearly increase of the instalment, in percent. */
    @Builder.Default
    @Column(name = "step_up_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal stepUpPercent = BigDecimal.ZERO;

    @Column(name = "emi_amount", precision = 15, scale = 2)
    private BigDecimal emiAmount;

    @Column(name = "emi_start_date")
    private LocalDate emiStartDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 30)
    private AccountStatus accountStatus;

    /** Days past due of the oldest unpaid instalment, stored by the classification run; 0 when nothing is overdue. */
    @Builder.Default
    @Column(name = "days_past_due", nullable = false)
    private Integer daysPastDue = 0;

    /** Money received that matches no demand yet; applied when the next instalment falls due. */
    @Builder.Default
    @Column(name = "advance_balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal advanceBalance = BigDecimal.ZERO;

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
     * How instalments are computed.
     * <ul>
     *   <li>EMI - level equated instalments (reducing balance)</li>
     *   <li>EQUAL_PRINCIPAL - same principal every instalment, so the instalment falls over time</li>
     *   <li>INTEREST_ONLY - interest every instalment, all principal with the last (bullet)</li>
     *   <li>BALLOON - level instalments that leave balloon_percent of the principal for the last one</li>
     *   <li>STEP_UP_EMI - instalment grows by step_up_percent every year</li>
     * </ul>
     */
    public enum ScheduleType { EMI, EQUAL_PRINCIPAL, INTEREST_ONLY, BALLOON, STEP_UP_EMI }

    /** Months between instalments. */
    public enum RepaymentFrequency {
        MONTHLY(1), QUARTERLY(3), HALF_YEARLY(6), YEARLY(12);

        private final int months;

        RepaymentFrequency(int months) {
            this.months = months;
        }

        public int months() {
            return months;
        }
    }

    /**
     * DB stores these as 'ACT/365', 'ACT/ACT', 'ACT/360', '30/360', '30E/360' (see V1 / V11 CHECK constraints).
     * Java enum names can't contain '/', so mapping is handled by DayCountConventionConverter.
     */
    public enum DayCountConvention {
        ACT_365,
        ACT_ACT,
        ACT_360,
        /** 30/360 US (bond basis) */
        THIRTY_360,
        /** 30E/360 (Eurobond basis) */
        THIRTY_E_360
    }

    public enum AccountStatus {
        PENDING_DISBURSEMENT, ACTIVE,
        NPA_SMA0, NPA_SMA1, NPA_SMA2,
        NPA_SUBSTANDARD, NPA_DOUBTFUL, NPA_LOSS,
        CLOSED, FORECLOSED, WRITTEN_OFF
    }
}
