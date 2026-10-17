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

/** One line of where a payment (or an advance being applied) went: a bucket of a demand, or the advance itself. */
@Entity
@Table(name = "payment_allocation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "allocation_id")
    private Long allocationId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** Empty for money that went to principal or interest outside a demand (part-payment, foreclosure) and for advances. */
    @Column(name = "demand_id")
    private Long demandId;

    @Column(name = "installment_number")
    private Integer installmentNumber;

    /** The payment this line belongs to; empty when an advance is applied to a newly raised demand. */
    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "bucket", nullable = false, length = 10)
    private Bucket bucket;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private Source source;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum Bucket { PENALTY, INTEREST, PRINCIPAL, ADVANCE }

    /** PAYMENT: money from the payment itself. ADVANCE: money taken from the account's advance balance. */
    public enum Source { PAYMENT, ADVANCE }
}
